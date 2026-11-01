package dev.codingagent.task;

import java.time.Instant;
import java.util.List;

import dev.codingagent.json.Json;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.model.Conversation;
import dev.codingagent.plan.PlanStep;
import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunRules;
import dev.codingagent.run.RunStatus;
import dev.codingagent.storage.StateStore;

/** Selects one ready task and commits completion together with runtime-owned evidence. */
public final class TaskService {
    private final StateStore store;

    public TaskService(StateStore store) {
        this.store = store;
    }

    public static TaskState singleTask(String goal) {
        var expected = new ExpectedResult("Verify the requested outcome with a successful Bash command after any file changes",
                List.of(new TaskCheck("Bash", null, null)));
        return new TaskState(new PlanStep("main", goal, List.of("list_files", "read_file", "write_file", "Bash"),
                List.of(), expected));
    }

    public void startNext(String runId) {
        store.update(runId, state -> {
            RunRules.requireActiveRun(state);
            if (state.phase != RunPhase.EXECUTION) {
                throw new IllegalStateException("Run has no executable task graph");
            }
            if (state.currentTaskId != null) {
                TaskRules.current(state);
                return null;
            }
            TaskState next = state.tasks.stream().filter(task -> task.status == TaskStatus.PENDING
                    && TaskRules.dependenciesComplete(state, task)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("No pending task has successful dependencies"));
            next.status = TaskStatus.RUNNING;
            next.startedAt = Instant.now().toString();
            state.currentTaskId = next.definition.id();
            state.messages.add(ChatMessage.user("Work only on the current task. Its dependencies are complete.\n"
                    + Json.write(next.definition) + "\nUse tools to obtain the required evidence, then return a short text summary. "
                    + "Stop as soon as this task's checks are satisfied, even if later tasks are still needed for the overall goal. "
                    + "The runtime selects the next task. Do not perform work assigned to later tasks."));
            return null;
        });
    }

    public boolean completeCurrent(String runId) {
        return store.update(runId, state -> {
            RunRules.requireActiveRun(state);
            TaskState task = TaskRules.current(state);
            String summary = Conversation.finalText(state.messages).orElseThrow(
                    () -> new IllegalStateException("Task completion requires a final assistant message"));
            try {
                task.evidence = TaskEvidenceCollector.collect(state, task);
            } catch (IllegalArgumentException missingEvidence) {
                state.messages.add(ChatMessage.user("Cannot complete task " + task.definition.id() + ": "
                        + missingEvidence.getMessage() + ". Use actual API tool_calls now to satisfy the checks. "
                        + "A code block or a description of a command does not change files or execute tests. "
                        + "Satisfy only the checks of the current task using its available tools, then return a short summary. "
                        + "Do not perform later tasks yet."));
                return false;
            }
            task.status = TaskStatus.COMPLETED;
            task.finishedAt = Instant.now().toString();
            task.summary = summary;
            state.currentTaskId = null;
            if (state.tasks.stream().allMatch(saved -> saved.status == TaskStatus.COMPLETED)) {
                RunRules.applyStatusChange(state, RunStatus.COMPLETED);
            }
            return true;
        });
    }
}
