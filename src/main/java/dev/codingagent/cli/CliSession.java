package dev.codingagent.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.codingagent.agent.Agent;
import dev.codingagent.agent.ExecutionHook;
import dev.codingagent.model.ChatCompletionsClient;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.model.ModelClient;
import dev.codingagent.model.ModelConfig;
import dev.codingagent.model.ScriptedModelClient;
import dev.codingagent.storage.StateStore;
import dev.codingagent.tools.ToolDefinition;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;

/** Shared construction for run, plan and resume; the model is only opened when needed. */
record CliSession(StateStore store, ToolRegistry tools, Map<String, String> options, int maxTurns) {
    static CliSession open(Map<String, String> options) throws IOException {
        int maxTurns = Integer.parseInt(options.getOrDefault("--max-turns", "20"));
        if (maxTurns < 1) {
            throw new IllegalArgumentException("--max-turns must be positive");
        }
        var config = CliOptions.config(options);
        var tools = ToolRegistry.defaults(config, ToolLimits.defaults());
        return new CliSession(new StateStore(config), tools, Map.copyOf(options), maxTurns);
    }

    String run(String runId, PrintStream out) throws IOException, InterruptedException {
        ModelClient model = new ModelClient() {
            private ModelClient delegate;

            @Override
            public ChatMessage generate(List<ChatMessage> history, List<ToolDefinition> definitions)
                    throws IOException, InterruptedException {
                return delegate().generate(history, definitions);
            }

            @Override
            public ChatMessage generateStructured(List<ChatMessage> history, ObjectNode schema)
                    throws IOException, InterruptedException {
                return delegate().generateStructured(history, schema);
            }

            private ModelClient delegate() throws IOException {
                if (delegate == null) {
                    delegate = options.containsKey("--script")
                            ? ScriptedModelClient.fromFile(Path.of(options.get("--script")))
                            : new ChatCompletionsClient(ModelConfig.fromEnvironment(System.getenv()));
                }
                return delegate;
            }
        };
        ExecutionHook hook = (call, result) -> {
            if (call.toolCallId().equals(options.get("--crash-after-call"))) {
                throw new IOException("Simulated crash after " + call.toolCallId() + "; result was not saved");
            }
        };
        return new Agent(store, model, tools, maxTurns, hook, state -> TaskView.print(state, out)).run(runId);
    }
}
