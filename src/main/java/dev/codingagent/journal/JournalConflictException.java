package dev.codingagent.journal;

public final class JournalConflictException extends IllegalStateException {
    public JournalConflictException(String message) {
        super("Journal conflict: " + message);
    }
}
