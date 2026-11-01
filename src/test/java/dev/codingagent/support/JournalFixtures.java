package dev.codingagent.support;

import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import dev.codingagent.json.Json;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.ToolResult;

public final class JournalFixtures {
    private JournalFixtures() {
    }

    public static String start(ProjectFixture project) {
        return project.activeRun();
    }

    public static ToolRequest write(String id, String path, String content) {
        var args = JsonNodeFactory.instance.objectNode().put("path", path).put("content", content);
        return new ToolRequest(id, "write_file", Json.write(args));
    }

    public static ToolRequest bash(String id, String command) {
        var args = JsonNodeFactory.instance.objectNode().put("command", command).put("cwd", ".");
        return new ToolRequest(id, "Bash", Json.write(args));
    }

    public static ToolResult unfinished(ProjectFixture project, ToolRegistry tools, String runId, ToolRequest request) {
        var definition = tools.describe(request, project.store.load(runId).currentTaskId);
        project.runs.recordAssistantTurn(runId, ModelReplies.calls(request), List.of(definition));
        project.calls.startAttempt(runId, request.id());
        return tools.execute(definition);
    }

    public static ToolResult execute(ProjectFixture project, ToolRegistry tools, String runId, ToolRequest request) {
        ToolResult result = unfinished(project, tools, runId, request);
        int attempt = project.store.load(runId).calls.get(request.id()).latestAttempt().number();
        return project.calls.finishAttempt(runId, request.id(), attempt, result).result();
    }
}
