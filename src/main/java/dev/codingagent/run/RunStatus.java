package dev.codingagent.run;

public enum RunStatus {
    PLANNING,
    AWAITING_APPROVAL,
    EXECUTING,
    RECOVERY_REQUIRED,
    COMPLETED,
    FAILED,
    ROLLED_BACK;

    public boolean canTransitionTo(RunStatus next) {
        if (next == ROLLED_BACK) {
            return this != ROLLED_BACK;
        }
        return switch (this) {
            case PLANNING -> next == AWAITING_APPROVAL || next == RECOVERY_REQUIRED || next == FAILED;
            case AWAITING_APPROVAL -> next == EXECUTING || next == FAILED;
            case EXECUTING -> next == COMPLETED || next == RECOVERY_REQUIRED || next == FAILED;
            case RECOVERY_REQUIRED -> next == PLANNING || next == EXECUTING || next == FAILED;
            case COMPLETED, FAILED, ROLLED_BACK -> false;
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == ROLLED_BACK;
    }
}
