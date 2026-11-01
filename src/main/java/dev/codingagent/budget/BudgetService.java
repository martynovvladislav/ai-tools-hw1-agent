package dev.codingagent.budget;

import java.time.Instant;
import java.util.List;

import dev.codingagent.run.RunRules;
import dev.codingagent.storage.StateStore;

/** Transaction boundaries for model requests and all-or-nothing tool batches. */
public final class BudgetService {
    private final StateStore store;

    public BudgetService(StateStore store) {
        this.store = store;
    }

    public void beginModelRequest(String runId) {
        store.update(runId, state -> {
            RunRules.requireActiveRun(state);
            var budget = state.budget;
            if (!budget.results.isEmpty()) {
                throw new IllegalStateException("Tool results must be resolved before requesting the model");
            }
            BudgetRules.requireRemaining("model_requests", 1, budget.limits.modelRequests() - budget.modelRequests);
            budget.modelRequests++;
            budget.modelRequestPending = true;
            budget.modelRequestStartedAt = Instant.now().toString();
            return null;
        });
    }

    public void reserveBatch(String runId, List<String> callIds) {
        store.update(runId, state -> {
            RunRules.requireActiveRun(state);
            BudgetRules.reserveBatch(state, callIds);
            return null;
        });
    }

    public int outputAllowance(String runId, String callId) {
        return BudgetRules.allowance(store.load(runId), callId);
    }
}
