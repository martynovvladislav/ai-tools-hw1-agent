package dev.codingagent.budget;

public final class BudgetExceededException extends IllegalStateException {
    public BudgetExceededException(String resource, long needed, long remaining) {
        super("Budget exhausted: " + resource + " (needed " + needed + ", remaining " + remaining + ")");
    }
}
