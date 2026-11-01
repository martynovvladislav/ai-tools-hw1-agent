package dev.codingagent.journal.rollback;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

import dev.codingagent.journal.JournalConflictException;
import dev.codingagent.journal.JournalState;
import dev.codingagent.journal.diff.PathChange;
import dev.codingagent.journal.snapshot.PathImage;
import dev.codingagent.journal.snapshot.TreeSnapshot;
import dev.codingagent.journal.snapshot.WorkspaceTree;

/** Validates the entire restore set before writing. Never recursively deletes a directory. */
public final class RollbackFiles {
    private final WorkspaceTree workspace;

    public RollbackFiles(WorkspaceTree workspace) {
        this.workspace = workspace;
    }

    public void preflight(JournalState journal, TreeSnapshot current) throws IOException {
        List<String> conflicts = new ArrayList<>();
        var target = new TreeMap<>(current.paths());
        for (PathChange change : journal.paths.values()) {
            workspace.resolve(change.path());
            PathImage actual = current.paths().get(change.path());
            PathImage expected = change.after();
            boolean accepted = Objects.equals(actual, expected);
            if (journal.rollback != null) {
                accepted |= Objects.equals(actual, change.before());
                accepted |= actual == null && expected != null && change.before() != null
                        && expected.directory() != change.before().directory();
            }
            if (!accepted) {
                conflicts.add(change.path());
            }
            if (change.before() == null) {
                target.remove(change.path());
            } else {
                target.put(change.path(), change.before());
            }
        }
        for (String path : target.keySet()) {
            int slash = path.lastIndexOf('/');
            while (slash >= 0) {
                String parent = path.substring(0, slash);
                PathImage image = target.get(parent);
                if (image == null || !image.directory()) {
                    conflicts.add("parent of " + path + ": " + parent);
                    break;
                }
                slash = parent.lastIndexOf('/');
            }
        }
        if (!conflicts.isEmpty()) {
            throw new JournalConflictException("rollback refused before writing any path: " + String.join(", ", conflicts));
        }
    }

    public void restore(JournalState journal, TreeSnapshot current, Consumer<String> afterPath) throws IOException {
        var deepest = journal.paths.values().stream().sorted(Comparator.comparingInt((PathChange change) -> depth(change.path()))
                .reversed().thenComparing(PathChange::path)).toList();
        for (PathChange change : deepest) {
            PathImage actual = current.paths().get(change.path());
            if (actual != null && !actual.directory() && (change.before() == null || change.before().directory())) {
                Files.delete(workspace.resolve(change.path()));
                afterPath.accept(change.path());
            }
        }
        for (PathChange change : deepest) {
            PathImage actual = current.paths().get(change.path());
            if (actual != null && actual.directory() && (change.before() == null || !change.before().directory())) {
                Files.delete(workspace.resolve(change.path()));
                afterPath.accept(change.path());
            }
        }
        for (PathChange change : deepest.reversed()) {
            PathImage actual = current.paths().get(change.path());
            if (change.before() != null && change.before().directory() && (actual == null || !actual.directory())) {
                Files.createDirectory(workspace.resolve(change.path()));
                afterPath.accept(change.path());
            }
        }
        for (PathChange change : deepest.reversed()) {
            if (change.before() != null && !change.before().directory()
                    && !Objects.equals(current.paths().get(change.path()), change.before())) {
                Files.write(workspace.resolve(change.path()), change.before().bytes());
                afterPath.accept(change.path());
            }
        }
    }

    private static int depth(String path) {
        return (int) path.chars().filter(character -> character == '/').count();
    }
}
