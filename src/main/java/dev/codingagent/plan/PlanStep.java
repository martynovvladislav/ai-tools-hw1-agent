package dev.codingagent.plan;

import java.util.HashSet;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import dev.codingagent.task.ExpectedResult;

public record PlanStep(String id, String description, List<String> tools,
        @JsonProperty("depends_on") List<String> dependsOn,
        @JsonProperty("expected_result") ExpectedResult expectedResult) {
    public PlanStep {
        if (description == null || description.isBlank() || tools == null || tools.isEmpty()
                || tools.stream().anyMatch(tool -> tool == null || tool.isBlank())) {
            throw new IllegalArgumentException("Plan step requires a description and tool names");
        }
        tools = List.copyOf(tools);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || expectedResult == null) {
            throw new IllegalArgumentException("Task requires a valid ID and expected_result");
        }
        if (dependsOn.stream().anyMatch(value -> value == null || value.isBlank())
                || new HashSet<>(dependsOn).size() != dependsOn.size() || dependsOn.contains(id)) {
            throw new IllegalArgumentException("Invalid task dependencies");
        }
    }
}
