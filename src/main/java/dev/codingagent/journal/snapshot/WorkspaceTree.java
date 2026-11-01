package dev.codingagent.journal.snapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.TreeMap;

/** No generated-file exclusions: source, tests, hidden files and generated files are tracked. */
public final class WorkspaceTree {
    private static final long MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PATHS = 1000;
    private final Path root;

    public WorkspaceTree(Path workspace) throws IOException {
        root = workspace.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !root.toRealPath().equals(root)) {
            throw new IOException("Workspace must be a real directory without links");
        }
    }

    public TreeSnapshot capture() throws IOException {
        var paths = new TreeMap<String, PathImage>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private long size;

            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                requireRealPath(directory);
                if (!directory.equals(root)) {
                    paths.put(relative(directory), PathImage.folder());
                    checkLimit();
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                requireRealPath(file);
                if (!attributes.isRegularFile()) {
                    throw new IOException("Snapshot does not support links or special files: " + relative(file));
                }
                if (attributes.size() > MAX_BYTES - size) {
                    throw new IOException("Workspace snapshot exceeds 2 MiB");
                }
                byte[] bytes;
                try (var input = Files.newInputStream(file)) {
                    bytes = input.readNBytes((int) (MAX_BYTES - size + 1));
                }
                size += bytes.length;
                if (size > MAX_BYTES) {
                    throw new IOException("Workspace snapshot exceeds 2 MiB");
                }
                paths.put(relative(file), PathImage.file(bytes));
                checkLimit();
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            private void checkLimit() throws IOException {
                if (paths.size() > MAX_PATHS) {
                    throw new IOException("Workspace snapshot exceeds 1000 paths");
                }
            }
        });
        return new TreeSnapshot(paths);
    }

    public Path resolve(String key) throws IOException {
        validateKey(key);
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Journal path escapes workspace");
        }
        for (Path current = target; current != null && current.startsWith(root); current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                requireRealPath(current);
            }
        }
        return target;
    }

    private String relative(Path path) throws IOException {
        String key = root.relativize(path).toString().replace(java.io.File.separatorChar, '/');
        validateKey(key);
        return key;
    }

    private void requireRealPath(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !path.toRealPath().equals(path.toAbsolutePath().normalize())) {
            throw new IOException("Snapshot refuses a linked path: " + path);
        }
    }

    private static void validateKey(String key) throws IOException {
        if (key == null || key.isEmpty() || key.contains("\\") || key.contains(":")) {
            throw new IOException("Invalid relative journal path: " + key);
        }
        for (String part : key.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IOException("Invalid relative journal path: " + key);
            }
        }
    }
}
