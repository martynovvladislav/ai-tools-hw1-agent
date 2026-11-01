package dev.codingagent.tools.execution;

import com.fasterxml.jackson.annotation.JsonIgnore;

import dev.codingagent.tools.ToolResult;

public record ToolAttempt(
        int number,
        AttemptStatus status,
        String startedAt,
        String finishedAt,
        ToolResult result
) {
    public ToolAttempt {
        if (number < 1 || status == null || (status == AttemptStatus.READY) != (startedAt == null)) {
            throw new IllegalArgumentException("Invalid attempt");
        }

        if (status.isFinished()) {
            validateFinishedAttempt(status, finishedAt, result);
        } else if (result != null || finishedAt != null) {
            throw new IllegalArgumentException("Unfinished attempt must not have a result");
        }
    }

    // This is computed from status, so it must not add a field to persisted JSON.
    @JsonIgnore
    public boolean isFinished() {
        return status.isFinished();
    }

    private static void validateFinishedAttempt(AttemptStatus status, String finishedAt, ToolResult result) {
        if (result == null || finishedAt == null) {
            throw new IllegalArgumentException("Finished attempt must have a result and finish time");
        }

        boolean expectedSuccess = status == AttemptStatus.SUCCEEDED;
        if (result.success() != expectedSuccess) {
            throw new IllegalArgumentException("Attempt status does not match its result");
        }
    }
}
