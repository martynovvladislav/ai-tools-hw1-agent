package dev.codingagent.tools;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.codingagent.config.AgentConfig;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.tools.bash.BashTool;
import dev.codingagent.tools.execution.ToolCall;
import dev.codingagent.tools.files.ListFilesTool;
import dev.codingagent.tools.files.ReadFileTool;
import dev.codingagent.tools.files.WorkspaceFiles;
import dev.codingagent.tools.files.WriteFileTool;

/** Owns trusted tool metadata and converts tool/argument failures into model-visible results. */
public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> availableTools) {
        for (Tool tool : availableTools) {
            if (tools.putIfAbsent(tool.definition().name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + tool.definition().name());
            }
        }
    }

    public static ToolRegistry defaults(AgentConfig config, ToolLimits limits) throws IOException {
        WorkspaceFiles files = new WorkspaceFiles(config.workspace());
        return new ToolRegistry(List.of(new ListFilesTool(files, limits.outputBytes()),
                new ReadFileTool(files, limits.outputBytes()), new WriteFileTool(files),
                new BashTool(config, files, limits)));
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }

    public List<ToolDefinition> planningDefinitions() {
        return definitions().stream().filter(tool -> !tool.sideEffect() && !tool.name().equals("Bash")).toList();
    }

    public boolean canRetry(ToolCall call) {
        Tool tool = tools.get(call.name());
        return tool != null && call.retrySafe() && tool.definition().retrySafe()
                && call.sideEffect() == tool.definition().sideEffect();
    }

    public ToolCall describe(ToolRequest request) {
        return describe(request, null);
    }

    public ToolCall describe(ToolRequest request, String taskId) {
        Tool tool = tools.get(request.name());
        boolean sideEffect = tool != null && tool.definition().sideEffect();
        boolean retrySafe = tool != null && tool.definition().retrySafe();
        return new ToolCall(request.id(), request.name(), request.argumentsJson(), sideEffect, retrySafe, taskId);
    }

    public ToolResult execute(ToolCall call) {
        return execute(call, ToolLimits.defaults().outputBytes());
    }

    public ToolResult execute(ToolCall call, int outputBytes) {
        return ResultLimiter.limit(executeTool(call, outputBytes), outputBytes);
    }

    private ToolResult executeTool(ToolCall call, int outputBytes) {
        Tool tool = tools.get(call.name());
        if (tool == null) {
            return ToolResult.error("Unknown tool: " + call.name());
        }
        try {
            var arguments = tool.definition().validate(call.argumentsJson());
            return tool.execute(arguments, outputBytes);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Tool execution interrupted");
        } catch (IOException | RuntimeException exception) {
            return ToolResult.error(exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }
}
