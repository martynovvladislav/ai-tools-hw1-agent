package dev.codingagent.tools.bash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import dev.codingagent.tools.ToolLimits;

/** Process lifecycle is separate from Bash argument parsing and tool result formatting. */
public final class ProcessRunner {
    public ProcessResult run(ProcessBuilder builder, String script, ToolLimits limits)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + limits.timeout().toNanos();
        Process process = builder.start();
        LimitedOutput output = new LimitedOutput(limits.outputBytes());
        Thread stdout = Thread.ofVirtual().start(() -> output.capture(process.getInputStream(), false));
        Thread stderr = Thread.ofVirtual().start(() -> output.capture(process.getErrorStream(), true));
        Thread stdin = Thread.ofVirtual().start(() -> writeScript(process, script));
        try {
            boolean exited = process.waitFor(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            if (!exited) {
                stopTree(process);
            }

            joinUntil(stdout, deadline);
            joinUntil(stderr, deadline);
            joinUntil(stdin, deadline);
            boolean timedOut = !exited || stdout.isAlive() || stderr.isAlive() || stdin.isAlive();
            if (timedOut) {
                stopTree(process);
                long cleanupDeadline = System.nanoTime() + Duration.ofMillis(200).toNanos();
                joinUntil(stdout, cleanupDeadline);
                joinUntil(stderr, cleanupDeadline);
                joinUntil(stdin, cleanupDeadline);
            }
            if (!timedOut && output.readFailure() != null) {
                throw output.readFailure();
            }
            int exitCode = process.isAlive() ? -1 : process.exitValue();
            return new ProcessResult(output.stdout(), output.stderr(), exitCode,
                    timedOut, output.truncated() || timedOut);
        } finally {
            stopTree(process);
        }
    }

    private static void writeScript(Process process, String script) {
        try (var input = process.getOutputStream()) {
            input.write(script.getBytes(StandardCharsets.UTF_8));
        } catch (IOException earlyExit) {
            // A command may exit before consuming the whole script; its exit code is the result.
        }
    }

    private static long remainingMillis(long deadline) {
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    private static void joinUntil(Thread thread, long deadline) throws InterruptedException {
        if (thread.isAlive() && System.nanoTime() < deadline) {
            thread.join(remainingMillis(deadline));
        }
    }

    private static void stopTree(Process process) {
        var children = process.descendants().toList().reversed();
        for (ProcessHandle child : children) {
            child.destroyForcibly();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
