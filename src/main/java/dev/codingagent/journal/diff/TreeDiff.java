package dev.codingagent.journal.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import dev.codingagent.journal.snapshot.TreeSnapshot;

public final class TreeDiff {
    private TreeDiff() {
    }

    public static List<PathChange> between(TreeSnapshot before, TreeSnapshot after) {
        var paths = new TreeSet<>(before.paths().keySet());
        paths.addAll(after.paths().keySet());
        List<PathChange> changes = new ArrayList<>();
        for (String path : paths) {
            var first = before.paths().get(path);
            var last = after.paths().get(path);
            if (!Objects.equals(first, last)) {
                changes.add(new PathChange(path, first, last));
            }
        }
        return changes;
    }
}
