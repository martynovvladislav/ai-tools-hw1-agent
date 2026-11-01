package dev.codingagent.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Parses the response envelope; malformed function arguments remain tool validation errors. */
public record AssistantTurn(ChatMessage message, List<ToolRequest> calls, String text) {
    public AssistantTurn {
        calls = List.copyOf(calls);
    }

    public static AssistantTurn parse(ChatMessage message) {
        if (!message.role().equals("assistant")) {
            throw new IllegalArgumentException("Model must return an assistant message");
        }
        var payload = message.json();
        var requested = payload.path("tool_calls");
        if (!requested.isMissingNode() && !requested.isNull() && !requested.isArray()) {
            throw new IllegalArgumentException("tool_calls must be an array");
        }
        List<ToolRequest> calls = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (var call : requested) {
            var function = call.path("function");
            if (!call.path("type").asText().equals("function") || !function.path("arguments").isTextual()) {
                throw new IllegalArgumentException("Expected a function call with string arguments");
            }
            ToolRequest request = new ToolRequest(call.path("id").asText(), function.path("name").asText(),
                    function.path("arguments").asText());
            if (!ids.add(request.id())) {
                throw new IllegalArgumentException("Duplicate tool call ID in one assistant turn: " + request.id());
            }
            calls.add(request);
        }
        String text = payload.path("content").isTextual() ? payload.path("content").asText() : "";
        if (calls.isEmpty() && text.isBlank()) {
            throw new IllegalArgumentException("Assistant returned neither tool calls nor final text");
        }
        return new AssistantTurn(message, calls, text);
    }
}
