package dev.codingagent.model;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.codingagent.json.Json;
import dev.codingagent.tools.ToolResult;

/** Owns the complete API message, including provider-specific fields. */
public record ChatMessage(JsonNode json) {
    private static final Set<String> ROLES = Set.of("system", "user", "assistant", "tool");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ChatMessage {
        if (!(json instanceof ObjectNode object) || !ROLES.contains(json.path("role").asText())) {
            throw new IllegalArgumentException("Expected a message object with a valid role");
        }
        json = Json.copy(object);
        if (json.path("role").asText().equals("tool")
                && (!json.path("tool_call_id").isTextual() || json.path("tool_call_id").asText().isBlank())) {
            throw new IllegalArgumentException("Tool message must include tool_call_id");
        }
    }

    @Override
    @JsonValue
    public JsonNode json() {
        return json.deepCopy();
    }

    public String role() {
        return json.path("role").asText();
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(JsonNodeFactory.instance.objectNode().put("role", "user").put("content", content));
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(JsonNodeFactory.instance.objectNode().put("role", "system").put("content", content));
    }

    public static ChatMessage tool(String callId, ToolResult result) {
        var payload = JsonNodeFactory.instance.objectNode().put("role", "tool").put("tool_call_id", callId)
                .put("content", result.contentJson());
        return new ChatMessage(payload);
    }
}
