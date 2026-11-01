package dev.codingagent.agent;

import java.io.IOException;
import java.util.List;

import dev.codingagent.budget.BudgetService;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.run.RunPhase;
import dev.codingagent.storage.StateStore;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.execution.ToolCall;
import dev.codingagent.tools.execution.ToolCallService;

/** Executes saved decisions; it never asks the model to choose the next step. */
final class ToolExecutor {
    private final StateStore store;
    private final ToolRegistry tools;
    private final ExecutionHook hook;
    private final ToolCallService calls;

    ToolExecutor(StateStore store, ToolRegistry tools, ExecutionHook hook) {
        this.store = store;
        this.tools = tools;
        this.hook = hook;
        calls = new ToolCallService(store);
    }

    void executePending(String runId, List<ToolRequest> pending) throws IOException, InterruptedException {
        var budget = new BudgetService(store);
        budget.reserveBatch(runId, pending.stream().map(ToolRequest::id).toList());
        for (ToolRequest request : pending) {
            var state = store.load(runId);
            var saved = state.calls.get(request.id());
            if (saved == null) {
                throw new IllegalStateException("Pending call has no saved definition: " + request.id());
            }
            ToolCall definition = saved.definition();
            if (state.phase == RunPhase.PLANNING && (definition.sideEffect() || definition.name().equals("Bash"))) {
                calls.rejectDuringPlanning(runId, request.id());
                continue;
            }
            var attempt = calls.startAttempt(runId, request.id());
            if (attempt.shouldExecute()) {
                var result = tools.execute(definition, budget.outputAllowance(runId, request.id()));
                hook.afterTool(definition, result);
                calls.finishAttempt(runId, request.id(), attempt.attempt().number(), result);
            } else {
                // A new assistant turn needs a reply even if execution is cached.
                calls.replayResult(runId, request.id());
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Agent execution interrupted");
            }
        }
    }
}
