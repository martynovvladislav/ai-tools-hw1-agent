package dev.codingagent.cli;

import java.util.Map;

import dev.codingagent.budget.BudgetLimits;

final class BudgetOptions {
    private BudgetOptions() {
    }

    static BudgetLimits parse(Map<String, String> options) {
        BudgetLimits defaults = BudgetLimits.defaults();
        return new BudgetLimits(value(options, "--max-model-requests", defaults.modelRequests()),
                value(options, "--max-tool-calls", defaults.toolCalls()),
                value(options, "--max-side-effects", defaults.sideEffects()),
                value(options, "--max-result-bytes", defaults.resultBytes()));
    }

    private static long value(Map<String, String> options, String name, long fallback) {
        try {
            return Long.parseLong(options.getOrDefault(name, Long.toString(fallback)));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Expected a nonnegative integer for " + name);
        }
    }
}
