package dev.codingagent.agent;

import java.io.IOException;

import dev.codingagent.tools.ToolResult;
import dev.codingagent.tools.execution.ToolCall;

/** Test/demo failure point: the tool has returned, but its result has not been saved. */
@FunctionalInterface
public interface ExecutionHook {
    ExecutionHook NONE = (call, result) -> { };

    void afterTool(ToolCall call, ToolResult result) throws IOException;
}
