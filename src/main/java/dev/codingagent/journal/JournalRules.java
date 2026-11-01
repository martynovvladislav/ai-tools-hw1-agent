package dev.codingagent.journal;

import java.time.Instant;
import java.util.Objects;

import dev.codingagent.journal.diff.PathChange;
import dev.codingagent.journal.diff.TreeDiff;
import dev.codingagent.journal.snapshot.TreeSnapshot;
import dev.codingagent.run.RunState;
import dev.codingagent.tools.execution.ToolCall;

/** Snapshot bookkeeping only. All changes here are made inside the caller's transaction. */
public final class JournalRules {
    private JournalRules() {
    }

    static boolean mutates(ToolCall call) {
        return call.sideEffect() || call.name().equals("Bash");
    }

    public static void requireExecutable(RunState state) {
        if (state.journal.rollback != null) {
            throw new IllegalStateException(state.journal.rollback.finishedAt == null
                    ? "Rollback has started; continue with rollback, not agent execution"
                    : "Run was rolled back; create a new run");
        }
    }

    static void requireUnchanged(RunState state, TreeSnapshot current) {
        requireExecutable(state);
        var differences = TreeDiff.between(state.journal.expectedTree, current);
        if (!differences.isEmpty()) {
            throw new JournalConflictException("workspace changed outside the run: "
                    + String.join(", ", differences.stream().map(PathChange::path).toList()));
        }
        for (var owned : state.journal.paths.values()) {
            if (!Objects.equals(owned.after(), current.paths().get(owned.path()))) {
                throw new JournalConflictException("excluded external change intersects an owned path: " + owned.path());
            }
        }
    }

    static JournalAction action(RunState state, String callId, int attempt) {
        return state.journal.actions.stream().filter(item -> item.toolCallId.equals(callId) && item.attempt == attempt)
                .findFirst().orElse(null);
    }

    public static void begin(RunState state, ToolCall call, int attempt, String startedAt, TreeSnapshot before) {
        if (!mutates(call)) {
            return;
        }
        if (before == null) {
            throw new IllegalStateException("Side-effect attempt requires a saved workspace snapshot");
        }
        requireUnchanged(state, before);
        if (state.journal.actions.stream().anyMatch(item -> item.status == JournalActionStatus.STARTED
                || item.status == JournalActionStatus.UNCERTAIN)) {
            throw new IllegalStateException("Resolve the previous journal action before another side effect");
        }
        var entry = new JournalAction();
        entry.toolCallId = call.toolCallId();
        entry.attempt = attempt;
        entry.startedAt = startedAt;
        entry.beforeTree = before;
        state.journal.actions.add(entry);
    }

    public static void finish(RunState state, ToolCall call, int attempt, TreeSnapshot after) {
        if (!mutates(call)) {
            return;
        }
        JournalAction entry = action(state, call.toolCallId(), attempt);
        if (entry == null || entry.status != JournalActionStatus.STARTED || after == null) {
            throw new IllegalStateException("Cannot finish an unstarted journal action");
        }
        entry.afterTree = after;
        entry.changes = TreeDiff.between(entry.beforeTree, after);
        entry.status = JournalActionStatus.RECORDED;
        entry.finishedAt = Instant.now().toString();
        include(state, entry);
    }

    public static void markUncertain(RunState state, String callId, int attempt) {
        JournalAction entry = action(state, callId, attempt);
        if (entry != null) {
            entry.status = JournalActionStatus.UNCERTAIN;
        }
    }

    static void observe(RunState state, JournalAction entry, TreeSnapshot current) {
        entry.afterTree = current;
        entry.changes = TreeDiff.between(entry.beforeTree, current);
        entry.observation = current.fingerprint();
    }

    public static void resolve(RunState state, String callId, int attempt, ChangeAttribution attribution,
            String observation, TreeSnapshot current) {
        JournalAction entry = action(state, callId, attempt);
        if (entry == null) {
            if (attribution != null || observation != null) {
                throw new IllegalArgumentException("This call has no uncertain journal action");
            }
            return;
        }
        if (entry.status != JournalActionStatus.UNCERTAIN || attribution == null || observation == null
                || !observation.equals(entry.observation)) {
            throw new IllegalStateException("Inspect changes with resume first, then supply --changes and --observation");
        }
        if (!Objects.equals(entry.afterTree, current)) {
            throw new JournalConflictException("workspace changed after inspection; inspect it again before deciding");
        }
        entry.attribution = attribution;
        entry.finishedAt = Instant.now().toString();
        entry.status = attribution == ChangeAttribution.INCLUDE ? JournalActionStatus.RECORDED : JournalActionStatus.EXCLUDED;
        if (attribution == ChangeAttribution.INCLUDE) {
            include(state, entry);
        } else {
            state.journal.expectedTree = current;
        }
    }

    private static void include(RunState state, JournalAction entry) {
        for (PathChange change : entry.changes) {
            PathChange previous = state.journal.paths.get(change.path());
            state.journal.paths.put(change.path(), new PathChange(change.path(),
                    previous == null ? change.before() : previous.before(), change.after()));
        }
        state.journal.expectedTree = entry.afterTree;
    }
}
