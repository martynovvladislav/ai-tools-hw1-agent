package dev.codingagent.task;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.codingagent.plan.PlanStep;

/** Validates all references and cycles before any task can start. */
public final class TaskGraph {
    private TaskGraph() {
    }

    public static void validate(List<PlanStep> steps) {
        Set<String> ids = new HashSet<>();
        for (var step : steps) {
            if (step.id() == null || !ids.add(step.id())) {
                throw new IllegalArgumentException("Task IDs must be present and unique");
            }
        }
        for (var step : steps) {
            if (!ids.containsAll(step.dependsOn())) {
                throw new IllegalArgumentException("Unknown dependency of task: " + step.id());
            }
        }
        Set<String> resolved = new HashSet<>();
        boolean changed;
        do {
            changed = false;
            for (var step : steps) {
                if (!resolved.contains(step.id()) && resolved.containsAll(step.dependsOn())) {
                    changed |= resolved.add(step.id());
                }
            }
        } while (changed);
        if (resolved.size() != steps.size()) {
            throw new IllegalArgumentException("Task dependencies contain a cycle");
        }
    }
}
