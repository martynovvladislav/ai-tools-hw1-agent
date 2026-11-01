package dev.codingagent.budget;

import dev.codingagent.tools.ResultLimiter;

/** An uncertain reservation is already spent; it is retained only to bound a possible skip result. */
public record OutputReservation(int bytes, boolean uncertain) {
    public OutputReservation {
        if (bytes < ResultLimiter.MIN_BYTES) {
            throw new IllegalArgumentException("Result reservation is too small for its JSON representation");
        }
    }
}
