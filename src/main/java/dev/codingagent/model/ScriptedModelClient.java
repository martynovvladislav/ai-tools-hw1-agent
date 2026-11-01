package dev.codingagent.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import dev.codingagent.json.Json;
import dev.codingagent.tools.ToolDefinition;

/** Deterministic model replacement for tests and reproducible console demonstrations. */
public final class ScriptedModelClient implements ModelClient {
    private final List<ChatMessage> replies;

    public ScriptedModelClient(List<ChatMessage> replies) {
        this.replies = List.copyOf(replies);
    }

    public static ScriptedModelClient fromFile(Path file) throws IOException {
        var root = Json.parseObject(Files.readString(file));
        if (!root.path("responses").isArray()) {
            throw new IllegalArgumentException("Script must contain a responses array");
        }
        List<ChatMessage> replies = new ArrayList<>();
        for (var message : root.path("responses")) {
            replies.add(new ChatMessage(message));
        }
        return new ScriptedModelClient(replies);
    }

    @Override
    public ChatMessage generate(List<ChatMessage> history, List<ToolDefinition> tools) {
        int index = 0;
        for (ChatMessage message : history) {
            if (message.role().equals("assistant")) {
                index++;
            }
        }
        if (index >= replies.size()) {
            String error = "Scripted model has no reply for turn " + index;
            for (ChatMessage previous : history.reversed()) {
                if (previous.role().equals("tool")) {
                    var message = previous.json();
                    var result = Json.parseObject(message.path("content").asText());
                    if (!result.path("success").asBoolean()) {
                        error += ". Last tool call " + message.path("tool_call_id").asText()
                                + " failed: " + Json.write(result.path("output"));
                    }
                    break;
                }
            }
            throw new IllegalStateException(error);
        }
        return replies.get(index);
    }
}
