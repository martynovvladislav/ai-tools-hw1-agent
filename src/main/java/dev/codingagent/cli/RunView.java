package dev.codingagent.cli;

import java.io.PrintStream;

import dev.codingagent.run.RunState;
import dev.codingagent.run.RunStatus;
import dev.codingagent.tools.execution.AttemptStatus;

final class RunView {
    private RunView() {
    }

    static void print(RunState state, PrintStream out) {
        out.println("run_id: " + state.runId);
        out.println("status: " + state.status + "; phase: " + state.phase);
        out.println("goal: " + state.goal);
        JournalView.summary(state, out);
        var budget = state.budget;
        out.println("budget model_requests: " + budget.modelRequests + "/" + budget.limits.modelRequests());
        out.println("budget tool_calls: " + budget.toolCalls + "/" + budget.limits.toolCalls());
        out.println("budget side_effects: " + budget.sideEffects + "/" + budget.limits.sideEffects());
        out.println("budget result_bytes: " + budget.resultBytes + " spent + " + budget.reservedBytes()
                + " reserved / " + budget.limits.resultBytes());
        if (state.plan != null) {
            out.println("plan goal: " + state.plan.goal());
            out.println("risks: " + String.join("; ", state.plan.risks()));
            out.println("assumptions: " + String.join("; ", state.plan.assumptions()));
            out.println("done when: " + state.plan.doneWhen());
        }
        TaskView.print(state, out);
        for (var call : state.calls.values()) {
            var attempt = call.latestAttempt();
            if (attempt != null && attempt.status() == AttemptStatus.UNCERTAIN) {
                out.println("uncertain call: " + call.definition().toolCallId() + " (" + call.definition().name()
                        + "), attempt " + attempt.number() + ", retry_safe=" + call.definition().retrySafe());
                out.println("arguments: " + call.definition().argumentsJson());
            }
        }
        if (state.status == RunStatus.AWAITING_APPROVAL) {
            out.println("Use approve or reject with --run-id and the same workspace/state-dir.");
        } else if (state.status == RunStatus.RECOVERY_REQUIRED) {
            out.println("Use resume --run-id ID --call-id ID --action retry|skip|fail.");
        } else if (state.status == RunStatus.COMPLETED) {
            out.println(state.messages.getLast().json().path("content").asText());
        }
    }
}
