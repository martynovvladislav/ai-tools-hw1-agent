package dev.codingagent.journal.rollback;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;

import dev.codingagent.journal.JournalActionStatus;
import dev.codingagent.journal.JournalConflictException;
import dev.codingagent.journal.snapshot.TreeSnapshot;
import dev.codingagent.journal.snapshot.WorkspaceTree;
import dev.codingagent.run.RunRules;
import dev.codingagent.run.RunState;
import dev.codingagent.run.RunStatus;
import dev.codingagent.run.UserDecision;
import dev.codingagent.storage.StateStore;
import dev.codingagent.tools.execution.AttemptStatus;

/** A persisted restore intent separates filesystem work from the final SQLite commit. */
public final class RollbackService {
    private final StateStore store;
    private final Consumer<String> afterPath;

    public RollbackService(StateStore store) {
        this(store, path -> { });
    }

    public RollbackService(StateStore store, Consumer<String> afterPath) {
        this.store = store;
        this.afterPath = afterPath;
    }

    public void rollback(String runId) throws IOException {
        RunState initial = store.load(runId);
        requireResolved(initial);
        if (initial.journal.rollback != null && initial.journal.rollback.finishedAt != null) {
            return; // An already completed rollback must not overwrite later external work.
        }
        var workspace = new WorkspaceTree(store.workspace());
        var files = new RollbackFiles(workspace);
        TreeSnapshot current = workspace.capture();
        files.preflight(initial.journal, current);
        if (initial.journal.rollback == null) {
            store.update(runId, state -> {
                requireResolved(state);
                if (state.journal.rollback != null || !state.journal.expectedTree.equals(initial.journal.expectedTree)) {
                    throw new JournalConflictException("journal changed while preparing rollback");
                }
                var intent = new RollbackState();
                intent.startedAt = Instant.now().toString();
                state.journal.rollback = intent;
                state.decisions.add(new UserDecision("ROLLBACK_REQUEST", "{}", intent.startedAt));
                return null;
            });
        }
        RunState prepared = store.load(runId);
        TreeSnapshot checked = workspace.capture();
        files.preflight(prepared.journal, checked); // Recheck all paths after the intent commit.
        files.restore(prepared.journal, checked, afterPath);
        TreeSnapshot restored = workspace.capture();
        for (var change : prepared.journal.paths.values()) {
            if (!Objects.equals(restored.paths().get(change.path()), change.before())) {
                throw new JournalConflictException("path changed during rollback: " + change.path());
            }
        }
        store.update(runId, state -> {
            state.journal.rollback.finishedAt = Instant.now().toString();
            state.journal.expectedTree = restored;
            RunRules.applyStatusChange(state, RunStatus.ROLLED_BACK);
            state.decisions.add(new UserDecision("ROLLBACK_COMPLETE", "{}", state.journal.rollback.finishedAt));
            return null;
        });
    }

    private static void requireResolved(RunState state) {
        boolean unresolvedCall = state.calls.values().stream().anyMatch(call -> call.latestAttempt() != null
                && (call.latestAttempt().status() == AttemptStatus.STARTED || call.latestAttempt().status() == AttemptStatus.UNCERTAIN));
        boolean unresolvedJournal = state.journal.actions.stream().anyMatch(action -> action.status == JournalActionStatus.STARTED
                || action.status == JournalActionStatus.UNCERTAIN);
        if (unresolvedCall || unresolvedJournal) {
            throw new IllegalStateException("Resolve uncertain calls and explicitly attribute their changes before rollback");
        }
    }
}
