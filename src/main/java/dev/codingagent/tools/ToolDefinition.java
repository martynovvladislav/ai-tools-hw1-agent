package dev.codingagent.tools;

import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.codingagent.json.Json;

/** All built-in tools use an object of required string arguments. */
public record ToolDefinition(
        String name, String description, List<String> arguments, boolean sideEffect, boolean retrySafe
) {
    public ToolDefinition {
        arguments = List.copyOf(arguments);
    }

    public ObjectNode apiSchema() {
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        ObjectNode properties = parameters.putObject("properties");
        var required = parameters.putArray("required");
        for (String argument : arguments) {
            properties.putObject(argument).put("type", "string");
            required.add(argument);
        }
        ObjectNode tool = JsonNodeFactory.instance.objectNode().put("type", "function");
        ObjectNode function = tool.putObject("function");
        function.put("name", name);
        function.put("description", description);
        function.set("parameters", parameters);
        return tool;
    }

    public ObjectNode validate(String json) {
        ObjectNode values = Json.parseObject(json);
        for (String argument : arguments) {
            if (!values.path(argument).isTextual()) {
                throw new IllegalArgumentException("Required string argument: " + argument);
            }
        }
        var names = values.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!arguments.contains(name)) {
                throw new IllegalArgumentException("Unknown argument: " + name);
            }
        }
        return values;
    }
}
