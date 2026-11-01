package dev.codingagent.tools.bash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.codingagent.json.Json;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TestProcesses;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.ToolResult;

class BashToolTest {
    @TempDir
    Path temporary;
    private ProjectFixture project;

    @BeforeEach
    void prepare() throws Exception {
        project = new ProjectFixture(temporary, TestProcesses.bash(), TestProcesses.python());
    }

    @Test
    void returnsWorkingDirectorySeparateStreamsAndExitCode() throws Exception {
        Files.createDirectory(project.config.workspace().resolve("sub dir"));
        String command = "\"$AGENT_PYTHON\" -c 'import os,sys; print(os.path.basename(os.getcwd())); "
                + "print(\"error-output\",file=sys.stderr); sys.exit(7)'";
        var result = run(command, "sub dir", ToolLimits.defaults());
        var payload = result.output();
        assertFalse(result.success());
        assertTrue(payload.path("stdout").asText().contains("sub dir"));
        assertTrue(payload.path("stderr").asText().contains("error-output"));
        assertEquals(7, payload.path("exit_code").asInt());
    }

    @Test
    void drainsBothLargePipesWhileKeepingOnlyTheOutputLimit() throws Exception {
        String command = "\"$AGENT_PYTHON\" -c 'import sys; sys.stdout.write(\"x\"*100000); "
                + "sys.stderr.write(\"y\"*100000)'";
        var result = run(command, ".", new ToolLimits(Duration.ofSeconds(10), 256));
        var payload = result.output();
        assertTrue(result.success(), result.output().toString());
        assertTrue(result.truncated());
        int size = payload.path("stdout").asText().getBytes(StandardCharsets.UTF_8).length
                + payload.path("stderr").asText().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(size <= 256);
    }

    @Test
    void preservesMultilineCommandsQuotesAndUtf8AndKeepsCommandStdinEmpty() throws Exception {
        String command = """
                printf '%s\\n' 'Привет: "двойные" и пробелы'
                printf '%s\\n' "it's a second line"
                cat
                printf '%s\\n' 'after cat'
                """;
        var result = run(command, ".", ToolLimits.defaults());
        assertTrue(result.success(), result.output().toString());
        assertEquals("Привет: \"двойные\" и пробелы\nit's a second line\nafter cat\n",
                result.output().path("stdout").asText());
    }

    @Test
    void timeoutReturnsControlWithoutWaitingForTheLongCommand() throws Exception {
        long start = System.nanoTime();
        var result = run("\"$AGENT_PYTHON\" -c 'import time; time.sleep(5)'", ".",
                new ToolLimits(Duration.ofMillis(500), 1024));
        assertFalse(result.success());
        assertTrue(result.output().path("timed_out").asBoolean());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 4);
    }

    private ToolResult run(String command, String cwd, ToolLimits limits) throws Exception {
        var arguments = JsonNodeFactory.instance.objectNode().put("command", command).put("cwd", cwd);
        ToolRegistry registry = ToolRegistry.defaults(project.config, limits);
        return registry.execute(registry.describe(new ToolRequest("shell", "Bash", Json.write(arguments))));
    }
}
