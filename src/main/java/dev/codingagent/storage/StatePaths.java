package dev.codingagent.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;

import dev.codingagent.config.AgentConfig;

/** Resolves the workspace and keeps the database outside its file tree. */
record StatePaths(Path workspace, Path databaseFile) {
    static StatePaths prepare(AgentConfig config) {
        try {
            Path workspace = config.workspace().toRealPath();
            if (!Files.isDirectory(workspace)) {
                throw new IllegalArgumentException("Workspace must be a directory");
            }

            Path stateDirectory = resolveFuturePath(config.stateDirectory());
            if (stateDirectory.startsWith(workspace) || workspace.startsWith(stateDirectory)) {
                throw new IllegalArgumentException("Workspace and state directory must be separate directories");
            }

            Files.createDirectories(stateDirectory);
            Path databaseFile = stateDirectory.resolve("runs.sqlite");
            if (Files.isSymbolicLink(databaseFile)) {
                throw new IllegalArgumentException("Database file must not be a symbolic link");
            }
            return new StatePaths(workspace, databaseFile);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot prepare state paths", exception);
        }
    }

    private static Path resolveFuturePath(Path path) throws IOException {
        ArrayDeque<Path> missingParts = new ArrayDeque<>();
        Path existingParent = path.toAbsolutePath().normalize();
        while (!Files.exists(existingParent, LinkOption.NOFOLLOW_LINKS)) {
            missingParts.addFirst(existingParent.getFileName());
            existingParent = existingParent.getParent();
        }

        Path resolved = existingParent.toRealPath();
        for (Path part : missingParts) {
            resolved = resolved.resolve(part);
        }
        return resolved;
    }
}
