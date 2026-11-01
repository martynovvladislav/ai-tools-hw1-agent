package dev.codingagent.tools.execution;

import java.util.ArrayList;
import java.util.List;

/** One immutable definition and the attempts owned by this run's snapshot. */
public record ToolCallState(ToolCall definition, List<ToolAttempt> attempts) {
    public ToolCallState {
        if (definition == null) {
            throw new IllegalArgumentException("Call definition is required");
        }

        attempts = new ArrayList<>(attempts);
        for (int index = 0; index < attempts.size(); index++) {
            ToolAttempt attempt = attempts.get(index);
            if (attempt == null || attempt.number() != index + 1) {
                throw new IllegalArgumentException("Attempt numbers must be consecutive starting at one");
            }
        }
    }

    public ToolCallState(ToolCall definition) {
        this(definition, List.of());
    }

    public ToolAttempt latestAttempt() {
        if (attempts.isEmpty()) {
            return null;
        }
        return attempts.getLast();
    }

    public void replaceLatestAttempt(ToolAttempt attempt) {
        if (latestAttempt() == null || latestAttempt().number() != attempt.number()) {
            throw new IllegalArgumentException("Cannot replace a different attempt");
        }
        attempts.set(attempts.size() - 1, attempt);
    }
}
