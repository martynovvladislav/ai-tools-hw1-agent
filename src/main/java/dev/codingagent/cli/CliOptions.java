package dev.codingagent.cli;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import dev.codingagent.config.AgentConfig;

/** Shared parsing for the small set of named CLI options. */
final class CliOptions {
    private CliOptions() {
    }

    static Map<String, String> parse(String[] args, Set<String> allowed) {
        Map<String, String> options = new HashMap<>();
        for (int index = 1; index < args.length; index += 2) {
            String name = args[index];
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("Unknown option: " + name);
            }
            if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + name);
            }
            if (options.putIfAbsent(name, args[index + 1]) != null) {
                throw new IllegalArgumentException("Duplicate option: " + name);
            }
        }
        return options;
    }

    static AgentConfig config(Map<String, String> options) {
        AgentConfig defaults = AgentConfig.defaults();
        return new AgentConfig(Path.of(options.getOrDefault("--workspace", defaults.workspace().toString())),
                Path.of(options.getOrDefault("--state-dir", defaults.stateDirectory().toString())),
                options.getOrDefault("--bash", defaults.bashExecutable()),
                options.getOrDefault("--python", defaults.pythonExecutable()));
    }

    static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required option: " + name);
        }
        return value;
    }
}
