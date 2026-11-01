package dev.codingagent.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import dev.codingagent.budget.BudgetService;
import dev.codingagent.journal.JournalRules;
import dev.codingagent.json.Json;
import dev.codingagent.model.AssistantTurn;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.model.Conversation;
import dev.codingagent.model.ModelClient;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.plan.PlanService;
import dev.codingagent.plan.PlanSchema;
import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunRules;
import dev.codingagent.run.RunService;
import dev.codingagent.run.RunState;
import dev.codingagent.run.RunStatus;
import dev.codingagent.storage.StateStore;
import dev.codingagent.task.TaskService;
import dev.codingagent.task.TaskStatus;
import dev.codingagent.tools.ToolDefinition;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.execution.ToolCall;

/** Model decision -> persisted calls -> external execution -> persisted results. */
public final class Agent {
    private final StateStore store;
    private final RunService runs;
    private final ToolExecutor executor;
    private final ToolRegistry tools;
    private final ModelClient model;
    private final int maxModelTurns;
    private final Consumer<RunState> taskProgress;

    public Agent(StateStore store, ModelClient model, ToolRegistry tools, int maxModelTurns) {
        this(store, model, tools, maxModelTurns, ExecutionHook.NONE);
    }

    public Agent(StateStore store, ModelClient model, ToolRegistry tools, int maxModelTurns, ExecutionHook hook) {
        this(store, model, tools, maxModelTurns, hook, state -> { });
    }

    public Agent(StateStore store, ModelClient model, ToolRegistry tools, int maxModelTurns, ExecutionHook hook,
            Consumer<RunState> taskProgress) {
        if (maxModelTurns < 1) {
            throw new IllegalArgumentException("Model turn limit must be positive");
        }
        this.store = store;
        this.model = model;
        this.tools = tools;
        this.maxModelTurns = maxModelTurns;
        this.taskProgress = taskProgress;
        runs = new RunService(store);
        executor = new ToolExecutor(store, tools, hook);
    }

    public String run(String runId) throws IOException, InterruptedException {
        var existing = store.load(runId);
        JournalRules.requireExecutable(existing);
        if (existing.status == RunStatus.COMPLETED) {
            return Conversation.finalText(existing.messages).orElseThrow(
                    () -> new IllegalStateException("Completed run has no final assistant answer"));
        }
        RunRules.requireActiveRun(existing);
        int modelTurns = 0;
        while (true) {
            var state = store.load(runId);
            if (state.phase == RunPhase.EXECUTION && state.currentTaskId == null) {
                new TaskService(store).startNext(runId);
                state = store.load(runId);
                taskProgress.accept(state);
            }
            List<ToolRequest> pending = Conversation.pendingCalls(state.messages);
            if (!pending.isEmpty()) {
                executor.executePending(runId, pending);
                continue;
            }
            if (requestDifferentApproach(runId, state)) {
                continue;
            }
            var finalText = Conversation.finalText(state.messages);
            if (finalText.isPresent()) {
                if (state.phase == RunPhase.PLANNING) {
                    try {
                        return Json.write(new PlanService(store).saveDraft(runId, finalText.get()));
                    } catch (IllegalArgumentException invalidPlan) {
                        new PlanService(store).requestDraft(runId, invalidPlan.getMessage());
                        continue;
                    }
                }
                if (new TaskService(store).completeCurrent(runId)) {
                    var updated = store.load(runId);
                    taskProgress.accept(updated);
                    if (updated.status == RunStatus.COMPLETED) {
                        return finalText.get();
                    }
                }
                continue;
            }
            if (modelTurns >= maxModelTurns) {
                throw new IllegalStateException("Model turn limit reached: " + maxModelTurns);
            }

            var definitionsForModel = definitionsForModel(state);
            new BudgetService(store).beginModelRequest(runId);
            boolean formattingPlan = state.phase == RunPhase.PLANNING && state.planDraftRequested;
            ChatMessage reply = formattingPlan
                    ? model.generateStructured(List.copyOf(state.messages), PlanSchema.json())
                    : model.generate(List.copyOf(state.messages), definitionsForModel);
            AssistantTurn turn = AssistantTurn.parse(reply);
            List<ToolCall> definitions = new ArrayList<>();
            for (ToolRequest request : turn.calls()) {
                definitions.add(tools.describe(request, state.phase == RunPhase.PLANNING ? null : state.currentTaskId));
            }
            runs.recordAssistantTurn(runId, reply, definitions);
            modelTurns++;
        }
    }

    private List<ToolDefinition> definitionsForModel(RunState state) {
        if (state.phase == RunPhase.PLANNING) {
            return tools.planningDefinitions();
        }
        if (state.plan == null) {
            return tools.definitions();
        }
        var current = state.tasks.stream().filter(task -> task.status == TaskStatus.RUNNING).findFirst().orElseThrow();
        return tools.definitions().stream().filter(tool -> current.definition.tools().contains(tool.name())).toList();
    }

    private boolean requestDifferentApproach(String runId, RunState state) {
        if (!state.messages.getLast().role().equals("tool")) {
            return false;
        }
        var replies = state.messages.stream().filter(message -> message.role().equals("tool")).toList();
        if (replies.size() < 2) {
            return false;
        }
        var latest = state.calls.get(replies.getLast().json().path("tool_call_id").asText());
        var previous = state.calls.get(replies.get(replies.size() - 2).json().path("tool_call_id").asText());
        if (latest.latestAttempt().result().success() || previous.latestAttempt().result().success()
                || !latest.definition().name().equals(previous.definition().name())
                || !latest.definition().argumentsJson().equals(previous.definition().argumentsJson())
                || !Objects.equals(latest.definition().taskId(), previous.definition().taskId())) {
            return false;
        }
        runs.appendMessage(runId, ChatMessage.user("Repeated tool failure: " + latest.definition().name()
                + " with the same arguments failed twice. Inspect the error and relevant files or environment "
                + "before choosing your next action. Do not keep repeating the failed call without investigating. "
                + "Preserve the requested behavior and do not weaken or bypass checks."));
        return true;
    }
}
