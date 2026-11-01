package dev.codingagent.tools.bash;

public record ProcessResult(String stdout, String stderr, int exitCode, boolean timedOut, boolean truncated) {
}
