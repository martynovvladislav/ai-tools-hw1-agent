package dev.codingagent.tools.execution;

/** False means reuse the saved result instead of executing the tool. */
public record AttemptStart(boolean shouldExecute, ToolAttempt attempt) {
}
