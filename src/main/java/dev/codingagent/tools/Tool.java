package dev.codingagent.tools;

import java.io.IOException;

import com.fasterxml.jackson.databind.node.ObjectNode;

public interface Tool {
    ToolDefinition definition();

    ToolResult execute(ObjectNode arguments, int outputBytes) throws IOException, InterruptedException;
}
