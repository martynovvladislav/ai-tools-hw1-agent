package dev.codingagent.tools.execution;

public enum AttemptStatus {
    READY,
    STARTED,
    SUCCEEDED,
    FAILED,
    UNCERTAIN;

    public boolean isFinished() {
        return this == SUCCEEDED || this == FAILED;
    }
}
