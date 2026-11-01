package dev.codingagent.support;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import dev.codingagent.model.ChatMessage;
import dev.codingagent.model.ToolRequest;

public final class ModelReplies {
    private ModelReplies() {
    }

    public static ChatMessage calls(ToolRequest... requests) {
        var message = JsonNodeFactory.instance.objectNode().put("role", "assistant");
        message.putNull("content");
        var calls = message.putArray("tool_calls");
        for (ToolRequest request : requests) {
            var call = calls.addObject().put("id", request.id()).put("type", "function");
            call.putObject("function").put("name", request.name()).put("arguments", request.argumentsJson());
        }
        return new ChatMessage(message);
    }

    public static ChatMessage text(String text) {
        var message = JsonNodeFactory.instance.objectNode().put("role", "assistant").put("content", text);
        return new ChatMessage(message);
    }
}
