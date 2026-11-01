package dev.codingagent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.codingagent.model.ToolRequest;
import dev.codingagent.support.ProjectFixture;

class ToolRegistryTest {
    @TempDir
    Path temporary;
    private ProjectFixture project;
    private ToolRegistry tools;

    @BeforeEach
    void prepare() throws Exception {
        project = new ProjectFixture(temporary);
        tools = ToolRegistry.defaults(project.config, ToolLimits.defaults());
    }

    @Test
    void listsReadsCreatesAndReplacesUtf8Files() throws Exception {
        assertTrue(execute("write_file", "{\"path\":\"a.txt\",\"content\":\"Привет\"}").success());
        var read = execute("read_file", "{\"path\":\"a.txt\"}");
        assertEquals("Привет", read.output().path("content").asText());
        var listing = execute("list_files", "{\"path\":\".\"}").output();
        assertEquals("a.txt", listing.path("files").path(0).asText());
        assertTrue(execute("write_file", "{\"path\":\"a.txt\",\"content\":\"\"}").success());
        assertEquals("", Files.readString(project.config.workspace().resolve("a.txt")));
    }

    @Test
    void validationErrorsDoNotWriteFiles() {
        assertFalse(execute("write_file", "{\"path\":\"a.txt\"}").success());
        assertFalse(execute("write_file", "{\"path\":7,\"content\":\"x\"}").success());
        assertFalse(execute("write_file", "{\"path\":\"a.txt\",\"content\":\"x\",\"extra\":true}").success());
        assertFalse(Files.exists(project.config.workspace().resolve("a.txt")));
    }

    @Test
    void unknownToolMalformedJsonAndMissingFileBecomeResults() {
        assertFalse(execute("not-a-tool", "{}").success());
        assertFalse(execute("read_file", "{").success());
        assertFalse(execute("read_file", "[]").success());
        assertFalse(execute("read_file", "{\"path\":\"missing\"}").success());
    }

    @Test
    void filePathsAndBashWorkingDirectoryCannotEscapeWorkspace() throws Exception {
        Path outside = temporary.resolve("outside.txt");
        Files.writeString(outside, "original");
        assertFalse(execute("read_file", "{\"path\":\"../outside.txt\"}").success());
        assertFalse(execute("write_file", "{\"path\":\"../outside.txt\",\"content\":\"changed\"}").success());
        assertFalse(execute("Bash", "{\"command\":\"printf not-started\",\"cwd\":\"..\"}").success());
        assertEquals("original", Files.readString(outside));
    }

    @Test
    void readLimitDoesNotSplitUtf8Characters() throws Exception {
        Files.writeString(project.config.workspace().resolve("text"), "АБВ");
        tools = ToolRegistry.defaults(project.config, new ToolLimits(Duration.ofSeconds(1), 3));
        var result = execute("read_file", "{\"path\":\"text\"}");
        assertTrue(result.truncated());
        String content = result.output().path("content").asText();
        assertEquals("А", content);
        assertTrue(content.getBytes(StandardCharsets.UTF_8).length <= 3);
    }

    @Test
    void schemaAndTrustedMetadataDescribeAllFourTools() {
        assertEquals(4, tools.definitions().size());
        var write = tools.describe(new ToolRequest("id", "write_file", "{}"));
        assertTrue(write.sideEffect());
        assertFalse(write.retrySafe());
        var read = tools.describe(new ToolRequest("read", "read_file", "{}"));
        assertFalse(read.sideEffect());
        assertTrue(read.retrySafe());
        for (var definition : tools.definitions()) {
            var schema = definition.apiSchema().path("function").path("parameters");
            assertEquals("object", schema.path("type").asText());
            assertFalse(schema.path("additionalProperties").asBoolean());
            assertEquals(definition.arguments().size(), schema.path("required").size());
        }
    }

    private ToolResult execute(String name, String arguments) {
        return tools.execute(tools.describe(new ToolRequest("test-call", name, arguments)));
    }
}
