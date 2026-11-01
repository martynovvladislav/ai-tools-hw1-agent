package dev.codingagent.budget;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunState;
import dev.codingagent.task.TaskRules;
import dev.codingagent.tools.ResultLimiter;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolResult;
import dev.codingagent.tools.execution.AttemptStatus;

/** Pure state changes called only inside StateStore.update; no SQL or external actions. */
public final class BudgetRules {
    private BudgetRules() {
    }

    static void requireRemaining(String resource, long needed, long remaining) {
        if (needed > remaining) {
            throw new BudgetExceededException(resource, needed, remaining);
        }
    }

    public static void reserveBatch(RunState state, List<String> callIds) {
        BudgetState budget = state.budget;
        List<String> fresh = new ArrayList<>();
        long effects = 0;
        for (String id : new LinkedHashSet<>(callIds)) {
            var call = state.calls.get(id);
            if (call == null) {
                throw new IllegalArgumentException("Unknown tool call: " + id);
            }
            TaskRules.requireOwner(state, call.definition());
            var latest = call.latestAttempt();
            if (latest != null && (latest.status() == AttemptStatus.STARTED || latest.status() == AttemptStatus.UNCERTAIN)) {
                throw new IllegalStateException("Recovery required: unresolved call: " + id);
            }
            if (!budget.results.containsKey(id)) {
                fresh.add(id);
                boolean blocked = state.phase == RunPhase.PLANNING
                        && (call.definition().sideEffect() || call.definition().name().equals("Bash"));
                if ((latest == null || !latest.isFinished()) && call.definition().sideEffect() && !blocked) {
                    effects++;
                }
            }
        }
        if (fresh.isEmpty()) {
            return;
        }
        long remainingBytes = budget.limits.resultBytes() - budget.resultBytes - budget.reservedBytes();
        requireRemaining("tool_calls", fresh.size(), budget.limits.toolCalls() - budget.toolCalls);
        requireRemaining("side_effects", effects, budget.limits.sideEffects() - budget.sideEffects);
        requireRemaining("result_bytes", (long) ResultLimiter.MIN_BYTES * fresh.size(), remainingBytes);
        int allowance = (int) Math.min(ToolLimits.defaults().outputBytes(), remainingBytes / fresh.size());
        budget.toolCalls += fresh.size();
        budget.sideEffects += effects;
        for (String id : fresh) {
            budget.results.put(id, new OutputReservation(allowance, false));
        }
    }

    public static int allowance(RunState state, String callId) {
        var reservation = state.budget.results.get(callId);
        if (reservation == null || reservation.uncertain()) {
            throw new IllegalStateException("No available output reservation for: " + callId);
        }
        return reservation.bytes();
    }

    public static ToolResult finishOutput(RunState state, String callId, ToolResult result) {
        BudgetState budget = state.budget;
        var reservation = budget.results.get(callId);
        if (reservation == null) {
            throw new IllegalStateException("No output reservation for: " + callId);
        }
        ToolResult bounded = ResultLimiter.limit(result, reservation.bytes());
        if (!reservation.uncertain()) {
            budget.resultBytes += ResultLimiter.bytes(bounded);
        }
        budget.results.remove(callId);
        return bounded;
    }

    public static void markUncertain(RunState state, String callId) {
        BudgetState budget = state.budget;
        var reservation = budget.results.get(callId);
        if (reservation == null || reservation.uncertain()) {
            throw new IllegalStateException("No active output reservation for interrupted call: " + callId);
        }
        budget.resultBytes += reservation.bytes();
        budget.results.put(callId, new OutputReservation(reservation.bytes(), true));
    }

    public static void reserveRetry(RunState state, String callId) {
        BudgetState budget = state.budget;
        var previous = budget.results.remove(callId);
        if (previous == null || !previous.uncertain()) {
            throw new IllegalStateException("Retry requires an uncertain output reservation");
        }
        reserveBatch(state, List.of(callId));
    }
}
