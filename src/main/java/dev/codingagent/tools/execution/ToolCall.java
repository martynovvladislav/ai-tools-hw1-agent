package dev.codingagent.tools.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.codingagent.json.Json;

/** Effect and retry metadata come from the tool registry, not the model. */
public record ToolCall(
        String toolCallId,
        String name,
        String argumentsJson,
        boolean sideEffect,
        boolean retrySafe,
        @JsonProperty("task_id") @JsonInclude(JsonInclude.Include.NON_NULL) String taskId
) {
    public ToolCall {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("Tool call ID must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        if (argumentsJson == null) {
            throw new IllegalArgumentException("Tool arguments must not be null");
        }
        argumentsJson = normalizeArguments(argumentsJson);
        if (taskId != null && taskId.isBlank()) {
            throw new IllegalArgumentException("Task ID must not be blank");
        }
    }

    public ToolCall(String toolCallId, String name, String argumentsJson, boolean sideEffect, boolean retrySafe) {
        this(toolCallId, name, argumentsJson, sideEffect, retrySafe, null);
    }

    private static String normalizeArguments(String argumentsJson) {
        try {
            return Json.object(argumentsJson);
        } catch (IllegalArgumentException invalidJson) {
            // Keep malformed arguments so validation can return a tool error to the model.
            return argumentsJson;
        }
    }
}
