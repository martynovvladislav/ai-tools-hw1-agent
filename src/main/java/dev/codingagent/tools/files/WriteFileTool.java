package dev.codingagent.tools.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.codingagent.tools.Tool;
import dev.codingagent.tools.ToolDefinition;
import dev.codingagent.tools.ToolResult;

public final class WriteFileTool implements Tool {
    private final WorkspaceFiles files;

    public WriteFileTool(WorkspaceFiles files) {
        this.files = files;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("write_file", "Create or replace a UTF-8 file; its parent directory must exist.",
                List.of("path", "content"), true, false);
    }

    @Override
    public ToolResult execute(ObjectNode arguments, int outputBytes) throws IOException {
        String path = arguments.path("path").asText();
        String content = arguments.path("content").asText();
        Files.writeString(files.writable(path), content, StandardCharsets.UTF_8);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("path", path);
        payload.put("bytes_written", content.getBytes(StandardCharsets.UTF_8).length);
        return new ToolResult(true, payload, false);
    }
}
