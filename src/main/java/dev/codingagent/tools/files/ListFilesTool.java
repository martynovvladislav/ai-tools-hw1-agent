package dev.codingagent.tools.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.codingagent.tools.Tool;
import dev.codingagent.tools.ToolDefinition;
import dev.codingagent.tools.ToolResult;

public final class ListFilesTool implements Tool {
    private final WorkspaceFiles files;
    private final int outputLimit;

    public ListFilesTool(WorkspaceFiles files, int outputLimit) {
        this.files = files;
        this.outputLimit = outputLimit;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("list_files", "List files recursively, relative to the workspace.",
                List.of("path"), false, true);
    }

    @Override
    public ToolResult execute(ObjectNode arguments, int outputBytes) throws IOException {
        int limit = Math.min(outputLimit, outputBytes);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        var names = payload.putArray("files");
        int size = 0;
        boolean truncated = false;
        try (var paths = Files.walk(files.directory(arguments.path("path").asText()))) {
            var iterator = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).sorted().iterator();
            while (iterator.hasNext()) {
                String name = files.relative(iterator.next());
                size += name.getBytes(StandardCharsets.UTF_8).length + 1;
                if (size > limit) {
                    truncated = true;
                    break;
                }
                names.add(name);
            }
        }
        return new ToolResult(true, payload, truncated);
    }
}
