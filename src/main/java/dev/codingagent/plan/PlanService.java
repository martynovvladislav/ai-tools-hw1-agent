package dev.codingagent.plan;

import java.time.Instant;

import dev.codingagent.json.Json;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.model.Instructions;
import dev.codingagent.run.RunRules;
import dev.codingagent.run.RunState;
import dev.codingagent.run.RunStatus;
import dev.codingagent.run.UserDecision;
import dev.codingagent.storage.StateStore;
import dev.codingagent.task.TaskState;

/** Planning and approval are separate persisted steps. Approval never starts a tool itself. */
public final class PlanService {
    private final StateStore store;

    public PlanService(StateStore store) {
        this.store = store;
    }

    public WorkPlan saveDraft(String runId, String json) {
        WorkPlan plan = WorkPlan.parse(json);
        return store.update(runId, state -> {
            if (state.status != RunStatus.PLANNING) {
                throw new IllegalStateException("Run is not planning");
            }
            state.plan = plan;
            state.planDraftRequested = false;
            state.tasks = plan.steps().stream().map(TaskState::new).toList();
            RunRules.applyStatusChange(state, RunStatus.AWAITING_APPROVAL);
            return plan;
        });
    }

    public void requestDraft(String runId, String feedback) {
        store.update(runId, state -> {
            if (state.status != RunStatus.PLANNING) {
                throw new IllegalStateException("Run is not planning");
            }
            state.planDraftRequested = true;
            state.messages.add(ChatMessage.user(Instructions.PLAN_FORMAT + "\nInvalid plan: " + feedback));
            return null;
        });
    }

    public void approve(String runId) {
        store.update(runId, state -> {
            requireAwaitingPlan(state);
            state.decisions.add(new UserDecision("APPROVE_PLAN", "{\"approved\":true}", Instant.now().toString()));
            state.messages.add(ChatMessage.user("I approve this plan. Plan Mode has ended; you are now in EXECUTION mode. "
                    + "The earlier instruction to return a JSON plan and avoid writes/Bash no longer applies. "
                    + "Use actual API tool_calls to execute only the current task selected by the runtime. "
                    + "After satisfying that task's checks, return a short text summary, not another plan. "
                    + "The runtime selects the next task. Preserve tests unless the original request allows changes.\n"
                    + Json.write(state.plan)));
            RunRules.applyStatusChange(state, RunStatus.EXECUTING);
            return null;
        });
    }

    public void reject(String runId) {
        store.update(runId, state -> {
            requireAwaitingPlan(state);
            state.decisions.add(new UserDecision("REJECT_PLAN", "{\"approved\":false}", Instant.now().toString()));
            RunRules.applyStatusChange(state, RunStatus.FAILED);
            return null;
        });
    }

    private static void requireAwaitingPlan(RunState state) {
        if (state.status != RunStatus.AWAITING_APPROVAL || state.plan == null) {
            throw new IllegalStateException("Run has no plan awaiting approval");
        }
    }
}
