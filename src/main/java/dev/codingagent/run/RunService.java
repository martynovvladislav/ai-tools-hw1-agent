package dev.codingagent.run;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.codingagent.budget.BudgetLimits;
import dev.codingagent.budget.BudgetState;
import dev.codingagent.journal.JournalService;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.model.Instructions;
import dev.codingagent.storage.StateStore;
import dev.codingagent.task.TaskService;
import dev.codingagent.tools.execution.ToolCall;
import dev.codingagent.tools.execution.ToolCallService;

/** Run creation, lifecycle, history and user decisions. No SQL or tool execution. */
public final class RunService {
    private final StateStore store;

    public RunService(StateStore store) {
        this.store = store;
    }

    public RunState create(String goal) {
        return create(goal, RunPhase.EXECUTION);
    }

    public RunState create(String goal, RunPhase phase) {
        return create(goal, phase, BudgetLimits.defaults());
    }

    public RunState create(String goal, RunPhase phase, BudgetLimits limits) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("Goal must not be blank");
        }
        if (phase == null) {
            throw new IllegalArgumentException("Initial phase is required");
        }
        if (limits == null) {
            throw new IllegalArgumentException("Budget limits are required");
        }

        RunState state = new RunState();
        state.runId = UUID.randomUUID().toString();
        state.goal = goal;
        state.phase = phase;
        state.status = phase == RunPhase.PLANNING ? RunStatus.PLANNING : RunStatus.EXECUTING;
        state.budget = new BudgetState(limits);
        state.tasks = phase == RunPhase.EXECUTION ? List.of(TaskService.singleTask(goal)) : List.of();
        state.journal = JournalService.initialState(store.workspace());
        state.workspace = store.workspace().toString();
        state.createdAt = Instant.now().toString();
        state.updatedAt = state.createdAt;
        state.messages.add(ChatMessage.system(Instructions.SYSTEM));
        state.messages.add(ChatMessage.user(goal));
        if (phase == RunPhase.PLANNING) {
            state.messages.add(ChatMessage.user(Instructions.PLANNING));
        }
        store.create(state);
        return state;
    }

    public void changeStatus(String runId, RunStatus next) {
        if (next == RunStatus.ROLLED_BACK) {
            throw new IllegalStateException("Use RollbackService to restore files and record rollback");
        }
        store.update(runId, state -> {
            if (state.status == RunStatus.AWAITING_APPROVAL && next == RunStatus.EXECUTING) {
                throw new IllegalStateException("Use PlanService.approve to record explicit approval");
            }
            RunRules.applyStatusChange(state, next);
            return null;
        });
    }

    public void appendMessage(String runId, ChatMessage message) {
        store.update(runId, state -> {
            RunRules.requireMutableRun(state);
            state.messages.add(message);
            return null;
        });
    }

    public void recordAssistantTurn(String runId, ChatMessage message, List<ToolCall> definitions) {
        if (!message.role().equals("assistant")) {
            throw new IllegalArgumentException("Expected an assistant message");
        }
        store.update(runId, state -> {
            RunRules.requireActiveRun(state);
            ToolCallService.registerInState(state, definitions);
            state.messages.add(message);
            state.budget.modelRequestPending = false;
            return null;
        });
    }
}
