package dev.codingagent.cli;

import java.io.PrintStream;

import dev.codingagent.journal.JournalActionStatus;
import dev.codingagent.journal.diff.DiffRenderer;
import dev.codingagent.journal.diff.TreeDiff;
import dev.codingagent.journal.snapshot.TreeSnapshot;
import dev.codingagent.run.RunState;

final class JournalView {
    private JournalView() {
    }

    static void summary(RunState state, PrintStream out) {
        out.println("journal: " + state.journal.actions.size() + " actions, " + state.journal.paths.size() + " owned paths");
        if (state.journal.rollback != null) {
            out.println(state.journal.rollback.finishedAt == null ? "Rollback is pending; continue with rollback."
                    : "Rollback completed; recorded diffs and task evidence remain historical.");
        }
        for (var action : state.journal.actions) {
            if (action.status == JournalActionStatus.STARTED) {
                out.println("Unfinished journal action " + action.toolCallId + "; stop the executor, then use resume.");
            } else if (action.status == JournalActionStatus.UNCERTAIN) {
                out.println("Unattributed changes: " + action.toolCallId + "#" + action.attempt);
                if (action.observation == null) {
                    out.println("Inspect with resume before deciding whether these changes belong to the run.");
                } else {
                    out.print(DiffRenderer.render(action.changes));
                    out.println("observation: " + action.observation);
                    out.println("Supply --changes include|exclude --observation HASH with the recovery action.");
                }
            }
        }
    }

    static void diff(RunState state, TreeSnapshot current, PrintStream out) {
        out.println("Recorded changes for run " + state.runId + ":");
        out.print(DiffRenderer.render(state.journal.paths.values()));
        summary(state, out);
        for (var action : state.journal.actions) {
            if (action.status == JournalActionStatus.EXCLUDED) {
                out.println("Excluded observation: " + action.toolCallId + "#" + action.attempt);
                out.print(DiffRenderer.render(action.changes));
            }
        }
        boolean unresolved = state.journal.actions.stream().anyMatch(action -> action.status == JournalActionStatus.STARTED
                || action.status == JournalActionStatus.UNCERTAIN);
        boolean rollbackPending = state.journal.rollback != null && state.journal.rollback.finishedAt == null;
        if (!unresolved && !rollbackPending) {
            var outside = TreeDiff.between(state.journal.expectedTree, current);
            if (!outside.isEmpty()) {
                out.println("Current tree also differs from the last snapshot; these changes are not attributed:");
                out.print(DiffRenderer.render(outside));
            }
        }
    }
}
