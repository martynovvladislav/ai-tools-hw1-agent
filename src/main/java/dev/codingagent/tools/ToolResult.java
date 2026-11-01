package dev.codingagent.tools;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.codingagent.json.Json;

public record ToolResult(boolean success, ObjectNode output, boolean truncated) {
    public ToolResult {
        output = Json.copy(output);
    }

    public ToolResult(boolean success, String outputJson, boolean truncated) {
        this(success, Json.parseObject(outputJson), truncated);
    }

    @Override
    public ObjectNode output() {
        return output.deepCopy();
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, JsonNodeFactory.instance.objectNode().put("error", message), false);
    }

    public String contentJson() {
        return Json.write(this);
    }
}
