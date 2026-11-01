package dev.codingagent.tools.execution;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.codingagent.budget.BudgetRules;
import dev.codingagent.journal.JournalRules;
import dev.codingagent.journal.JournalService;
import dev.codingagent.journal.snapshot.TreeSnapshot;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunRules;
import dev.codingagent.run.RunState;
import dev.codingagent.run.RunStatus;
import dev.codingagent.storage.StateStore;
import dev.codingagent.task.TaskRules;
import dev.codingagent.tools.ToolResult;

/** Registers calls and records their attempts. The actual tool runs outside these methods. */
public final class ToolCallService {
    private final StateStore store;

    public ToolCallService(StateStore store) {
        this.store = store;
    }

    /** Registers definitions in the caller's transaction, together with the assistant message. */
    public static void registerInState(RunState state, List<ToolCall> batch) {
        RunRules.requireMutableRun(state);
        batch.forEach(call -> TaskRules.requireOwner(state, call));
        validateDefinitions(state, batch);
        for (ToolCall definition : batch) {
            state.calls.putIfAbsent(definition.toolCallId(), new ToolCallState(definition));
        }
    }

    public AttemptStart startAttempt(String runId, String callId) {
        TreeSnapshot before = new JournalService(store).beforeAttempt(runId, callId);
        return store.update(runId, state -> beginAttempt(state, callId, before));
    }

    public ToolAttempt finishAttempt(String runId, String callId, int number, ToolResult result) {
        TreeSnapshot after = new JournalService(store).afterAttempt(runId, callId);
        return store.update(runId, state -> completeAttempt(state, callId, number, result, after));
    }

    public void replayResult(String runId, String callId) {
        store.update(runId, state -> {
            RunRules.requireActiveRun(state);
            var latest = requireCall(state, callId).latestAttempt();
            TaskRules.requireOwner(state, requireCall(state, callId).definition());
            if (latest == null || !latest.isFinished()) {
                throw new IllegalStateException("Call has no saved result: " + callId);
            }
            BudgetRules.reserveBatch(state, List.of(callId));
            var bounded = BudgetRules.finishOutput(state, callId, latest.result());
            state.messages.add(ChatMessage.tool(callId, bounded));
            return null;
        });
    }

    public void rejectDuringPlanning(String runId, String callId) {
        store.update(runId, state -> {
            if (state.status != RunStatus.PLANNING) {
                throw new IllegalStateException("Only planning calls can be rejected here");
            }
            ToolCallState call = requireCall(state, callId);
            if (!call.definition().sideEffect() && !call.definition().name().equals("Bash")) {
                throw new IllegalArgumentException("This call is allowed during planning");
            }
            ToolAttempt latest = call.latestAttempt();
            if (latest != null && !latest.isFinished()) {
                throw new IllegalStateException("Recovery required: unresolved planning call");
            }
            BudgetRules.reserveBatch(state, List.of(callId));
            var result = latest == null
                    ? ToolResult.error("Plan Mode forbids changes and all Bash commands before approval") : latest.result();
            var bounded = BudgetRules.finishOutput(state, callId, result);
            if (latest == null) {
                String now = Instant.now().toString();
                call.attempts().add(new ToolAttempt(1, AttemptStatus.FAILED, now, now, bounded));
            }
            state.messages.add(ChatMessage.tool(callId, bounded));
            return null;
        });
    }

    public int markInterruptedAttempts(String runId) {
        return store.update(runId, state -> {
            int count = 0;
            for (ToolCallState call : state.calls.values()) {
                ToolAttempt latest = call.latestAttempt();
                if (latest != null && latest.status() == AttemptStatus.STARTED) {
                    BudgetRules.markUncertain(state, call.definition().toolCallId());
                    JournalRules.markUncertain(state, call.definition().toolCallId(), latest.number());
                    ToolAttempt uncertain = new ToolAttempt(
                            latest.number(), AttemptStatus.UNCERTAIN, latest.startedAt(), null, null);
                    call.replaceLatestAttempt(uncertain);
                    count++;
                }
            }
            if (count > 0 && !state.status.isTerminal()) {
                state.status = RunStatus.RECOVERY_REQUIRED;
            }
            return count;
        });
    }

    private static AttemptStart beginAttempt(RunState state, String callId, TreeSnapshot before) {
        ToolCallState call = requireCall(state, callId);
        TaskRules.requireOwner(state, call.definition());
        ToolAttempt latest = call.latestAttempt();
        if (latest != null) {
            if (latest.isFinished()) {
                return new AttemptStart(false, latest);
            }
            if (latest.status() != AttemptStatus.READY) {
                throw new IllegalStateException("Recovery required: call already started: " + callId);
            }
        }

        RunRules.requireActiveRun(state);
        requireAllowedInPhase(state, call.definition());
        BudgetRules.reserveBatch(state, List.of(callId));

        int number = latest == null ? 1 : latest.number();
        ToolAttempt attempt = new ToolAttempt(number, AttemptStatus.STARTED, Instant.now().toString(), null, null);
        JournalRules.begin(state, call.definition(), number, attempt.startedAt(), before);
        if (latest == null) {
            call.attempts().add(attempt);
        } else {
            call.replaceLatestAttempt(attempt);
        }
        return new AttemptStart(true, attempt);
    }

    private static ToolAttempt completeAttempt(RunState state, String callId, int number, ToolResult result, TreeSnapshot after) {
        ToolCallState call = requireCall(state, callId);
        ToolAttempt latest = call.latestAttempt();
        if (latest == null) {
            throw new IllegalStateException("Call has not started");
        }
        if (latest.number() != number) {
            throw new IllegalArgumentException("Protocol error: attempt number does not match");
        }
        if (latest.isFinished()) {
            if (!latest.result().equals(result)) {
                throw new IllegalArgumentException("Protocol error: attempt already has a different result");
            }
            return latest;
        }
        if (latest.status() == AttemptStatus.UNCERTAIN) {
            throw new IllegalStateException("Recovery required: uncertain attempt");
        }
        if (latest.status() != AttemptStatus.STARTED) {
            throw new IllegalStateException("Attempt has not started");
        }

        RunRules.requireActiveRun(state);
        JournalRules.finish(state, call.definition(), number, after);
        result = BudgetRules.finishOutput(state, callId, result);
        AttemptStatus status = result.success() ? AttemptStatus.SUCCEEDED : AttemptStatus.FAILED;
        ToolAttempt finished = new ToolAttempt(number, status, latest.startedAt(), Instant.now().toString(), result);
        call.replaceLatestAttempt(finished);
        state.messages.add(ChatMessage.tool(callId, result));
        return finished;
    }

    private static void validateDefinitions(RunState state, List<ToolCall> batch) {
        Map<String, ToolCall> definitions = new LinkedHashMap<>();
        for (ToolCallState call : state.calls.values()) {
            definitions.put(call.definition().toolCallId(), call.definition());
        }
        for (ToolCall definition : batch) {
            ToolCall existing = definitions.putIfAbsent(definition.toolCallId(), definition);
            if (existing != null && !existing.equals(definition)) {
                throw new IllegalArgumentException("Protocol error: tool call ID reused with different definition: "
                        + definition.toolCallId());
            }
        }
    }

    private static void requireAllowedInPhase(RunState state, ToolCall definition) {
        if (state.phase != RunPhase.PLANNING) {
            return;
        }
        if (definition.sideEffect() || definition.name().equals("Bash")) {
            throw new IllegalStateException("Planning permits only read-only tools and forbids Bash");
        }
    }

    private static ToolCallState requireCall(RunState state, String callId) {
        ToolCallState call = state.calls.get(callId);
        if (call == null) {
            throw new IllegalArgumentException("Unknown tool call: " + callId);
        }
        return call;
    }
}
