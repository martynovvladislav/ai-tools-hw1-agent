package dev.codingagent.tools.files;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.codingagent.tools.Tool;
import dev.codingagent.tools.ToolDefinition;
import dev.codingagent.tools.ToolResult;
import dev.codingagent.tools.bash.LimitedOutput;

public final class ReadFileTool implements Tool {
    private final WorkspaceFiles files;
    private final int outputLimit;

    public ReadFileTool(WorkspaceFiles files, int outputLimit) {
        this.files = files;
        this.outputLimit = outputLimit;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("read_file", "Read a UTF-8 file. Large results are explicitly truncated.",
                List.of("path"), false, true);
    }

    @Override
    public ToolResult execute(ObjectNode arguments, int outputBytes) throws IOException {
        int limit = Math.min(outputLimit, outputBytes);
        byte[] bytes;
        try (var input = Files.newInputStream(files.existing(arguments.path("path").asText()))) {
            bytes = input.readNBytes(limit + 1);
        }
        String content = LimitedOutput.decode(bytes, Math.min(limit, bytes.length));
        ObjectNode payload = JsonNodeFactory.instance.objectNode().put("content", content);
        return new ToolResult(true, payload, bytes.length > limit);
    }
}
