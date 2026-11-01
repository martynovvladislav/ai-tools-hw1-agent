package dev.codingagent.plan;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import dev.codingagent.json.Json;
import dev.codingagent.task.TaskGraph;

/** Structured output of planning; no execution or storage logic. */
public record WorkPlan(String goal, List<PlanStep> steps, List<String> risks, List<String> assumptions,
        @JsonProperty("done_when") String doneWhen) {
    public WorkPlan {
        if (goal == null || goal.isBlank() || doneWhen == null || doneWhen.isBlank()
                || steps == null || steps.isEmpty() || steps.stream().anyMatch(step -> step == null)) {
            throw new IllegalArgumentException("Plan requires a goal, steps and a completion criterion");
        }
        steps = List.copyOf(steps);
        TaskGraph.validate(steps);
        risks = requireStrings(risks, "risks");
        assumptions = requireStrings(assumptions, "assumptions");
    }

    public static WorkPlan parse(String json) {
        WorkPlan plan = Json.read(json, WorkPlan.class);
        for (var step : plan.steps()) {
            for (var check : step.expectedResult().checks()) {
                if (!check.tool().equals("any") && !step.tools().contains(check.tool())) {
                    throw new IllegalArgumentException("Task " + step.id() + " must list check tool in tools: " + check.tool());
                }
            }
        }
        return plan;
    }

    private static List<String> requireStrings(List<String> values, String field) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Plan requires nonblank entries in: " + field);
        }
        return List.copyOf(values);
    }
}
