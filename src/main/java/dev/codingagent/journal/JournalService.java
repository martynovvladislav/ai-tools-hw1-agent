package dev.codingagent.journal;

import java.io.IOException;
import java.nio.file.Path;

import dev.codingagent.journal.snapshot.TreeSnapshot;
import dev.codingagent.journal.snapshot.WorkspaceTree;
import dev.codingagent.storage.StateStore;

/** Filesystem reads happen before short transactions; snapshots are committed with action state. */
public final class JournalService {
    private final StateStore store;

    public JournalService(StateStore store) {
        this.store = store;
    }

    public static JournalState initialState(Path workspace) {
        var journal = new JournalState();
        journal.expectedTree = capture(workspace);
        return journal;
    }

    public TreeSnapshot beforeAttempt(String runId, String callId) {
        var state = store.load(runId);
        JournalRules.requireExecutable(state);
        var call = state.calls.get(callId);
        if (call == null || !JournalRules.mutates(call.definition())
                || (call.latestAttempt() != null && call.latestAttempt().isFinished())) {
            return null;
        }
        TreeSnapshot tree = currentTree();
        JournalRules.requireUnchanged(state, tree);
        return tree;
    }

    public TreeSnapshot afterAttempt(String runId, String callId) {
        var state = store.load(runId);
        var call = state.calls.get(callId);
        if (call == null || !JournalRules.mutates(call.definition())
                || call.latestAttempt() == null || call.latestAttempt().isFinished()) {
            return null;
        }
        return currentTree();
    }

    public void observePending(String runId) {
        var state = store.load(runId);
        if (state.journal.actions.stream().noneMatch(item -> item.status == JournalActionStatus.UNCERTAIN)) {
            return;
        }
        TreeSnapshot current = currentTree();
        store.update(runId, saved -> {
            for (var entry : saved.journal.actions) {
                if (entry.status == JournalActionStatus.UNCERTAIN) {
                    JournalRules.observe(saved, entry, current);
                }
            }
            return null;
        });
    }

    public TreeSnapshot beforeResolution(String runId, String callId) {
        var state = store.load(runId);
        var call = state.calls.get(callId);
        if (call == null || call.latestAttempt() == null
                || JournalRules.action(state, callId, call.latestAttempt().number()) == null) {
            return null;
        }
        return currentTree();
    }

    public TreeSnapshot currentTree() {
        return capture(store.workspace());
    }

    private static TreeSnapshot capture(Path workspace) {
        try {
            return new WorkspaceTree(workspace).capture();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot snapshot workspace: " + exception.getMessage(), exception);
        }
    }
}
