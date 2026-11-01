package dev.codingagent.run;

import dev.codingagent.journal.JournalRules;
import dev.codingagent.task.TaskRules;
import dev.codingagent.tools.execution.AttemptStatus;
import dev.codingagent.tools.execution.ToolAttempt;
import dev.codingagent.tools.execution.ToolCallState;

/** Run guards and transitions applied inside the caller's state transaction. */
public final class RunRules {
    private RunRules() {
    }

    public static void requireMutableRun(RunState state) {
        JournalRules.requireExecutable(state);
        if (state.status.isTerminal()) {
            throw new IllegalStateException("Run is already terminal: " + state.status);
        }
    }

    public static void requireActiveRun(RunState state) {
        JournalRules.requireExecutable(state);
        if (state.status != RunStatus.PLANNING && state.status != RunStatus.EXECUTING) {
            throw new IllegalStateException("Run is not active: " + state.status);
        }
    }

    public static void applyStatusChange(RunState state, RunStatus next) {
        if (state.status == next) {
            return;
        }
        if (!state.status.canTransitionTo(next)) {
            throw new IllegalStateException("Invalid run transition: " + state.status + " -> " + next);
        }
        if (next == RunStatus.COMPLETED || next == RunStatus.AWAITING_APPROVAL) {
            requireFinishedCalls(state);
        }
        if (next == RunStatus.COMPLETED) {
            TaskRules.requireAllComplete(state);
        } else if (next == RunStatus.FAILED || next == RunStatus.ROLLED_BACK) {
            TaskRules.failCurrent(state);
        }
        if (state.status == RunStatus.RECOVERY_REQUIRED && next != RunStatus.FAILED && next != RunStatus.ROLLED_BACK) {
            requireResolvedRecovery(state, next);
        }

        state.status = next;
        if (next == RunStatus.PLANNING) {
            state.phase = RunPhase.PLANNING;
        } else if (next == RunStatus.EXECUTING) {
            state.phase = RunPhase.EXECUTION;
        }
    }

    private static void requireFinishedCalls(RunState state) {
        for (ToolCallState call : state.calls.values()) {
            ToolAttempt latest = call.latestAttempt();
            if (latest == null || !latest.isFinished()) {
                throw new IllegalStateException("Run still has unfinished calls");
            }
        }
    }

    private static void requireResolvedRecovery(RunState state, RunStatus next) {
        for (ToolCallState call : state.calls.values()) {
            ToolAttempt latest = call.latestAttempt();
            if (latest != null && !latest.isFinished() && latest.status() != AttemptStatus.READY) {
                throw new IllegalStateException("Recovery required: unresolved attempts remain");
            }
        }

        RunStatus expected = RunStatus.EXECUTING;
        if (state.phase == RunPhase.PLANNING) {
            expected = RunStatus.PLANNING;
        }
        if (next != expected) {
            throw new IllegalStateException("Recovery must return to the saved phase");
        }
    }
}
