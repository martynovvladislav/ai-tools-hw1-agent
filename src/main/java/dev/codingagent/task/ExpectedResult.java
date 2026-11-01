package dev.codingagent.task;

import java.util.List;

public record ExpectedResult(String description, List<TaskCheck> checks) {
    public ExpectedResult {
        if (description == null || description.isBlank() || checks == null || checks.isEmpty()
                || checks.stream().anyMatch(check -> check == null)) {
            throw new IllegalArgumentException("Expected result requires a description and at least one tool check");
        }
        checks = List.copyOf(checks);
    }
}
