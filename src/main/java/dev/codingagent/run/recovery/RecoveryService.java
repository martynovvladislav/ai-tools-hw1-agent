package dev.codingagent.run.recovery;

import java.time.Instant;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import dev.codingagent.budget.BudgetRules;
import dev.codingagent.journal.ChangeAttribution;
import dev.codingagent.journal.JournalRules;
import dev.codingagent.journal.JournalService;
import dev.codingagent.json.Json;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunRules;
import dev.codingagent.run.RunStatus;
import dev.codingagent.run.UserDecision;
import dev.codingagent.storage.StateStore;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.ToolResult;
import dev.codingagent.tools.execution.AttemptStatus;
import dev.codingagent.tools.execution.ToolAttempt;

/** A recovery choice and its effect are committed in the same transaction. */
public final class RecoveryService {
    private final StateStore store;
    private final ToolRegistry tools;

    public RecoveryService(StateStore store, ToolRegistry tools) {
        this.store = store;
        this.tools = tools;
    }

    public void resolve(String runId, String callId, RecoveryAction action) {
        resolve(runId, callId, action, null, null);
    }

    public void resolve(String runId, String callId, RecoveryAction action, ChangeAttribution attribution, String observation) {
        if (action == null) {
            throw new IllegalArgumentException("Recovery action is required");
        }
        var current = new JournalService(store).beforeResolution(runId, callId);
        store.update(runId, state -> {
            if (state.status != RunStatus.RECOVERY_REQUIRED) {
                throw new IllegalStateException("Run does not require recovery");
            }
            var call = state.calls.get(callId);
            if (call == null || call.latestAttempt() == null
                    || call.latestAttempt().status() != AttemptStatus.UNCERTAIN) {
                throw new IllegalStateException("Call is not uncertain: " + callId);
            }
            ToolAttempt previous = call.latestAttempt();
            JournalRules.resolve(state, callId, previous.number(), attribution, observation, current);
            String now = Instant.now().toString();
            switch (action) {
                case RETRY -> {
                    if (!tools.canRetry(call.definition())) {
                        throw new IllegalStateException("Tool cannot be safely retried: " + call.definition().name());
                    }
                    call.attempts().add(new ToolAttempt(previous.number() + 1, AttemptStatus.READY, null, null, null));
                    BudgetRules.reserveRetry(state, callId);
                }
                case SKIP, FAIL -> {
                    ToolResult result = ToolResult.error(action == RecoveryAction.SKIP
                            ? "User marked the uncertain attempt as failed; continue"
                            : "User ended the run after the uncertain attempt");
                    result = BudgetRules.finishOutput(state, callId, result);
                    call.replaceLatestAttempt(new ToolAttempt(previous.number(), AttemptStatus.FAILED,
                            previous.startedAt(), now, result));
                    state.messages.add(ChatMessage.tool(callId, result));
                    if (action == RecoveryAction.FAIL) {
                        RunRules.applyStatusChange(state, RunStatus.FAILED);
                    }
                }
            }
            var payload = JsonNodeFactory.instance.objectNode().put("tool_call_id", callId)
                    .put("attempt", previous.number()).put("action", action.name());
            if (attribution != null) {
                payload.put("changes", attribution.name()).put("observation", observation);
            }
            state.decisions.add(new UserDecision("RECOVERY_" + action.name(), Json.write(payload), now));

            boolean unresolved = state.calls.values().stream().anyMatch(saved -> saved.latestAttempt() != null
                    && saved.latestAttempt().status() == AttemptStatus.UNCERTAIN);
            if (state.status == RunStatus.RECOVERY_REQUIRED && !unresolved) {
                RunStatus next = state.phase == RunPhase.PLANNING ? RunStatus.PLANNING : RunStatus.EXECUTING;
                RunRules.applyStatusChange(state, next);
            }
            return null;
        });
    }
}
