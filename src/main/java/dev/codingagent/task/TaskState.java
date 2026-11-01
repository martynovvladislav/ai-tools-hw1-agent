package dev.codingagent.task;

import java.util.ArrayList;
import java.util.List;

import dev.codingagent.plan.PlanStep;

public final class TaskState {
    public PlanStep definition;
    public TaskStatus status = TaskStatus.PENDING;
    public String startedAt;
    public String finishedAt;
    public String summary;
    public List<TaskEvidence> evidence = new ArrayList<>();

    public TaskState() {
    }

    public TaskState(PlanStep definition) {
        this.definition = definition;
    }
}
