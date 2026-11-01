package dev.codingagent.budget;

/** Limits for the entire run, including planning and recovery. */
public record BudgetLimits(long modelRequests, long toolCalls, long sideEffects, long resultBytes) {
    public BudgetLimits {
        if (modelRequests < 0 || toolCalls < 0 || sideEffects < 0 || resultBytes < 0) {
            throw new IllegalArgumentException("Budget limits must not be negative");
        }
    }

    public static BudgetLimits defaults() {
        return new BudgetLimits(40, 100, 30, 256 * 1024);
    }
}
