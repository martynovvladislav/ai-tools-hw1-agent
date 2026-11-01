package dev.codingagent.tools.bash;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.codingagent.config.AgentConfig;
import dev.codingagent.json.Json;
import dev.codingagent.tools.Tool;
import dev.codingagent.tools.ToolDefinition;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolResult;
import dev.codingagent.tools.files.WorkspaceFiles;

public final class BashTool implements Tool {
    private final AgentConfig config;
    private final WorkspaceFiles files;
    private final ToolLimits limits;

    public BashTool(AgentConfig config, WorkspaceFiles files, ToolLimits limits) {
        this.config = config;
        this.files = files;
        this.limits = limits;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("Bash", "Run a non-interactive Bash command in a workspace directory. "
                + "Use \"$AGENT_PYTHON\" for Python. Time and combined stdout/stderr are limited.",
                List.of("command", "cwd"), true, false);
    }

    @Override
    public ToolResult execute(ObjectNode arguments, int outputBytes) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(config.bashExecutable(), "--noprofile", "--norc",
                "-s");
        builder.directory(files.directory(arguments.path("cwd").asText()).toFile());
        builder.environment().put("AGENT_PYTHON", config.pythonExecutable().replace('\\', '/'));
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().remove("OPENAI_API_KEY");

        // Stdin preserves quotes across Java -> Git Bash on Windows. The group is parsed
        // before execution; commands inside it receive empty stdin instead of the script.
        String script = "{\n" + arguments.path("command").asText() + "\n} </dev/null\n";
        var processLimits = new ToolLimits(limits.timeout(), Math.min(limits.outputBytes(), outputBytes));
        var result = new ProcessRunner().run(builder, script, processLimits);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("stdout", result.stdout());
        payload.put("stderr", result.stderr());
        payload.put("exit_code", result.exitCode());
        payload.put("timed_out", result.timedOut());
        return new ToolResult(result.exitCode() == 0 && !result.timedOut(),
                Json.write(payload), result.truncated());
    }
}
