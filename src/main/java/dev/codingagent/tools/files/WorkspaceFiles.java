package dev.codingagent.tools.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Checks paths for file tools and Bash cwd; it is not an OS sandbox for shell commands. */
public final class WorkspaceFiles {
    private final Path root;

    public WorkspaceFiles(Path workspace) throws IOException {
        root = workspace.toRealPath();
    }

    public Path existing(String path) throws IOException {
        Path resolved = inside(path).toRealPath();
        requireInside(resolved);
        return resolved;
    }

    public Path directory(String path) throws IOException {
        Path resolved = existing(path);
        if (!Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Expected a directory: " + path);
        }
        return resolved;
    }

    public Path writable(String path) throws IOException {
        Path resolved = inside(path);
        requireInside(resolved.getParent().toRealPath());
        if (Files.isSymbolicLink(resolved) || Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Expected a regular file path: " + path);
        }
        return resolved;
    }

    public String relative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private Path inside(String path) {
        Path resolved = root.resolve(path).normalize();
        requireInside(resolved);
        return resolved;
    }

    private void requireInside(Path path) {
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside the workspace");
        }
    }
}
