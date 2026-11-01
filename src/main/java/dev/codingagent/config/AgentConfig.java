package dev.codingagent.config;

import java.nio.file.Files;
import java.nio.file.Path;

/** Paths and executable names shared by the CLI and runtime. */
public record AgentConfig(
        Path workspace,
        Path stateDirectory,
        String bashExecutable,
        String pythonExecutable
) {
    public AgentConfig {
        workspace = workspace.toAbsolutePath().normalize();
        stateDirectory = stateDirectory.toAbsolutePath().normalize();
        if (bashExecutable == null || bashExecutable.isBlank()) {
            throw new IllegalArgumentException("Bash executable must not be blank");
        }
        if (pythonExecutable == null || pythonExecutable.isBlank()) {
            throw new IllegalArgumentException("Python executable must not be blank");
        }
    }

    public static AgentConfig defaults() {
        Path gitBash = Path.of("C:/Program Files/Git/bin/bash.exe");
        String bash = Files.isRegularFile(gitBash) ? gitBash.toString() : "bash";
        return new AgentConfig(Path.of("workspace"), Path.of(".agent-state"), bash, "python3");
    }
}
