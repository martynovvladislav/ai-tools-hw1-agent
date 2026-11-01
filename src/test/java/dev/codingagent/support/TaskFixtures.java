package dev.codingagent.support;

import java.util.Arrays;
import java.util.List;

import dev.codingagent.json.Json;
import dev.codingagent.plan.PlanService;
import dev.codingagent.plan.PlanStep;
import dev.codingagent.plan.WorkPlan;
import dev.codingagent.run.RunPhase;
import dev.codingagent.task.ExpectedResult;
import dev.codingagent.task.TaskCheck;

public final class TaskFixtures {
    private TaskFixtures() {
    }

    public static PlanStep step(String id, List<String> dependencies, TaskCheck... checks) {
        return new PlanStep(id, "Task " + id, Arrays.stream(checks).map(TaskCheck::tool).distinct().toList(), dependencies,
                new ExpectedResult("Verified outcome of " + id, List.of(checks)));
    }

    public static WorkPlan plan(PlanStep... steps) {
        return new WorkPlan("Complete the task graph", List.of(steps), List.of("Incorrect results"),
                List.of("Tool results provide evidence"), "All checks pass");
    }

    public static String approvedRun(ProjectFixture project, PlanStep... steps) {
        String id = project.createRun("Execute verified tasks", RunPhase.PLANNING).runId;
        var plans = new PlanService(project.store);
        plans.saveDraft(id, Json.write(plan(steps)));
        plans.approve(id);
        return id;
    }
}
