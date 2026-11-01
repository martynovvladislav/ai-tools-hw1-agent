package dev.codingagent.task;

import dev.codingagent.tools.ToolResult;

/** Runtime-owned proof copied from an actual saved attempt when completing a task. */
public record TaskEvidence(TaskCheck check, String toolCallId, int attempt, String finishedAt, ToolResult result) {
}
