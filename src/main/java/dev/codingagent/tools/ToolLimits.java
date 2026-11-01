package dev.codingagent.tools;

import java.time.Duration;

public record ToolLimits(Duration timeout, int outputBytes) {
    public ToolLimits {
        if (timeout == null || timeout.isNegative() || timeout.isZero() || outputBytes < 1) {
            throw new IllegalArgumentException("Tool timeout and output limit must be positive");
        }
    }

    public static ToolLimits defaults() {
        return new ToolLimits(Duration.ofSeconds(10), 16 * 1024);
    }
}
