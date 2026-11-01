package dev.codingagent.budget;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Counters and outstanding output reservations, updated by BudgetRules in the same package. */
public final class BudgetState {
    public BudgetLimits limits;
    public long modelRequests;
    public long toolCalls;
    public long sideEffects;
    public long resultBytes;
    public boolean modelRequestPending;
    public String modelRequestStartedAt;
    public Map<String, OutputReservation> results = new LinkedHashMap<>();

    public BudgetState() {
    }

    public BudgetState(BudgetLimits limits) {
        this.limits = limits;
    }

    @JsonIgnore
    public long reservedBytes() {
        return results.values().stream().filter(reservation -> !reservation.uncertain())
                .mapToLong(OutputReservation::bytes).sum();
    }
}
