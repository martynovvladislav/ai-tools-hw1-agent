package dev.codingagent.model;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.codingagent.tools.ToolDefinition;

public interface ModelClient {
    ChatMessage generate(List<ChatMessage> history, List<ToolDefinition> tools) throws IOException, InterruptedException;

    default ChatMessage generateStructured(List<ChatMessage> history, ObjectNode schema)
            throws IOException, InterruptedException {
        return generate(history, List.of());
    }
}
