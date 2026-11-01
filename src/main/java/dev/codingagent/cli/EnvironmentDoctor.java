package dev.codingagent.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import dev.codingagent.config.AgentConfig;

/** Read-only environment checks; this is not the agent's Bash tool. */
final class EnvironmentDoctor {
    private final PrintStream out;

    EnvironmentDoctor(PrintStream out) {
        this.out = out;
    }

    int check(AgentConfig config) {
        out.println("Java: " + Runtime.version() + " (" + System.getProperty("java.home") + ")");
        out.println("Workspace: " + config.workspace());
        out.println("State directory (not created): " + config.stateDirectory());

        boolean workspaceExists = Files.isDirectory(config.workspace());
        if (!workspaceExists) {
            out.println("Workspace check: FAILED (directory does not exist)");
        }
        boolean bashAvailable = checkExecutable("Bash", config.bashExecutable());
        boolean pythonAvailable = !Thread.currentThread().isInterrupted()
                && checkExecutable("Python", config.pythonExecutable());
        boolean healthy = workspaceExists && bashAvailable && pythonAvailable;
        out.println(healthy ? "Environment check: OK" : "Environment check: FAILED");
        return healthy ? 0 : 1;
    }

    private boolean checkExecutable(String label, String executable) {
        Process process = null;
        try {
            // Discard version output so a full pipe cannot block process termination.
            process = new ProcessBuilder(executable, "--version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                out.println(label + " check: FAILED (5-second timeout)");
                return false;
            }
            if (process.exitValue() != 0) {
                out.println(label + " check: FAILED (exit code " + process.exitValue() + ")");
                return false;
            }
            out.println(label + ": OK (" + executable + ")");
            return true;
        } catch (IOException exception) {
            out.println(label + " check: FAILED (" + exception.getMessage() + ")");
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            out.println(label + " check: FAILED (interrupted)");
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
