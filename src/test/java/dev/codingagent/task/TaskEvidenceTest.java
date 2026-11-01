package dev.codingagent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.codingagent.agent.Agent;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.model.ScriptedModelClient;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.run.RunStatus;
import dev.codingagent.storage.StateStore;
import dev.codingagent.support.ModelReplies;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TaskFixtures;
import dev.codingagent.support.TestProcesses;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;

class TaskEvidenceTest {
    @TempDir
    Path temporary;
    private ProjectFixture project;
    private ToolRegistry tools;

    @BeforeEach
    void prepare() throws Exception {
        project = new ProjectFixture(temporary, TestProcesses.bash(), TestProcesses.python());
        tools = ToolRegistry.defaults(project.config, ToolLimits.defaults());
    }

    @Test
    void aModelClaimWithoutActualEvidenceDoesNotCompleteATaskOrUnblockItsDependent() {
        var first = TaskFixtures.step("first", List.of(), new TaskCheck("read_file", "a.txt", null));
        var second = TaskFixtures.step("second", List.of("first"), new TaskCheck("write_file", "a.txt", null));
        String id = TaskFixtures.approvedRun(project, first, second);
        var model = new ScriptedModelClient(List.of(ModelReplies.text("Everything is done")));
        assertThrows(IllegalStateException.class, () -> new Agent(project.store, model, tools, 1).run(id));
        var state = project.store.load(id);
        assertEquals(TaskStatus.RUNNING, state.tasks.getFirst().status);
        assertEquals(TaskStatus.PENDING, state.tasks.get(1).status);
        assertTrue(state.tasks.getFirst().evidence.isEmpty());
        assertTrue(state.messages.getLast().json().toString().contains("Cannot complete task first"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "truncated"})
    void failedOrTruncatedToolResultsCannotSatisfyACheck(String outcome) throws Exception {
        if (outcome.equals("truncated")) {
            Files.writeString(project.config.workspace().resolve("a.txt"), "large content".repeat(10_000));
        }
        String id = oneTask(new TaskCheck("read_file", "a.txt", null));
        var model = script(call("read", "read_file", "{\"path\":\"a.txt\"}"), ModelReplies.text("Read complete"));
        assertThrows(IllegalStateException.class, () -> new Agent(project.store, model, tools, 2).run(id));
        var state = project.store.load(id);
        assertEquals(TaskStatus.RUNNING, state.tasks.getFirst().status);
        assertTrue(state.tasks.getFirst().evidence.isEmpty());
        var result = state.calls.get("read").latestAttempt().result();
        assertTrue(!result.success() || result.truncated());
    }

    @Test
    void aSuccessfulResultForAnotherPathDoesNotCountAndTheModelCanCorrectIt() throws Exception {
        Files.writeString(project.config.workspace().resolve("a.txt"), "required content");
        Files.writeString(project.config.workspace().resolve("b.txt"), "other content");
        String id = oneTask(new TaskCheck("read_file", "a.txt", "required"));
        var model = script(call("wrong", "read_file", "{\"path\":\"b.txt\"}"), ModelReplies.text("Done"),
                call("right", "read_file", "{\"path\":\"a.txt\"}"), ModelReplies.text("Correct file verified"));
        new Agent(project.store, model, tools, 5).run(id);
        var state = project.store.load(id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals("right", state.tasks.getFirst().evidence.getFirst().toolCallId());
        assertTrue(state.messages.stream().anyMatch(message -> message.json().toString().contains("Cannot complete task")));
    }

    @Test
    void anotherTaskCannotBorrowEvidenceOrReuseItsToolCallId() throws Exception {
        Files.writeString(project.config.workspace().resolve("a.txt"), "content");
        var first = TaskFixtures.step("first", List.of(), new TaskCheck("read_file", "a.txt", null));
        var second = TaskFixtures.step("second", List.of("first"), new TaskCheck("read_file", "a.txt", null));
        String id = TaskFixtures.approvedRun(project, first, second);
        var read = call("same-id", "read_file", "{\"path\":\"a.txt\"}");
        var claim = script(read, ModelReplies.text("First done"), ModelReplies.text("Second also done"));
        assertThrows(IllegalStateException.class, () -> new Agent(project.store, claim, tools, 3).run(id));
        assertEquals(TaskStatus.COMPLETED, project.store.load(id).tasks.getFirst().status);
        assertEquals(TaskStatus.RUNNING, project.store.load(id).tasks.get(1).status);
        assertTrue(project.store.load(id).tasks.get(1).evidence.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> project.calls.startAttempt(id, "same-id"));
        var conflicting = script(read, ModelReplies.text("First done"), ModelReplies.text("Second also done"), read);
        assertThrows(IllegalArgumentException.class, () -> new Agent(project.store, conflicting, tools, 3).run(id));
        assertEquals("first", project.store.load(id).calls.get("same-id").definition().taskId());
        assertEquals(1, project.store.load(id).calls.get("same-id").attempts().size());
    }

    @Test
    void replayingAnOldBashResultAfterAWriteDoesNotCreateFreshVerification() throws Exception {
        String id = oneTask(new TaskCheck("Bash", ".", "VERIFIED"));
        var oldBash = call("old-check", "Bash", "{\"command\":\"printf VERIFIED\",\"cwd\":\".\"}");
        var write = call("write", "write_file", "{\"path\":\"a.txt\",\"content\":\"later change\"}");
        var freshBash = call("fresh-check", "Bash", "{\"command\":\"printf VERIFIED\",\"cwd\":\".\"}");
        var model = script(oldBash, write, oldBash, ModelReplies.text("Old result is enough"), freshBash,
                ModelReplies.text("Fresh check completed"));
        assertThrows(IllegalStateException.class, () -> new Agent(project.store, model, tools, 4).run(id));
        assertEquals(TaskStatus.RUNNING, project.store.load(id).tasks.getFirst().status);
        assertTrue(project.store.load(id).tasks.getFirst().evidence.isEmpty());
        new Agent(new StateStore(project.config), model, tools, 4).run(id);
        var finished = project.store.load(id);
        assertEquals("fresh-check", finished.tasks.getFirst().evidence.getFirst().toolCallId());
        assertEquals(1, finished.calls.get("old-check").attempts().size());
        assertEquals(3, finished.budget.sideEffects);
    }

    @Test
    void taskCompletionAndEvidenceRollbackTogetherAndResumeUsesSavedResults() throws Exception {
        Files.writeString(project.config.workspace().resolve("a.txt"), "content");
        String id = oneTask(new TaskCheck("read_file", "a.txt", null));
        executeSql("CREATE TRIGGER reject_completion AFTER UPDATE ON runs WHEN NEW.state_json LIKE '%\"status\":\"COMPLETED\"%' "
                + "BEGIN SELECT RAISE(ABORT, 'controlled failure'); END");
        var model = script(call("read", "read_file", "{\"path\":\"a.txt\"}"), ModelReplies.text("Done"));
        assertThrows(IllegalStateException.class, () -> new Agent(project.store, model, tools, 5).run(id));
        var unchanged = project.store.load(id);
        assertEquals(TaskStatus.RUNNING, unchanged.tasks.getFirst().status);
        assertTrue(unchanged.tasks.getFirst().evidence.isEmpty());
        assertTrue(unchanged.calls.get("read").latestAttempt().result().success());
        executeSql("DROP TRIGGER reject_completion");
        new Agent(new StateStore(project.config), (history, definitions) -> { throw new AssertionError("Unneeded model request"); },
                tools, 5).run(id);
        var finished = project.store.load(id);
        assertEquals(RunStatus.COMPLETED, finished.status);
        assertEquals(1, finished.tasks.getFirst().evidence.size());
        assertEquals(1, finished.budget.toolCalls);
        assertEquals(2, finished.budget.modelRequests);
    }

    private String oneTask(TaskCheck check) {
        return TaskFixtures.approvedRun(project, TaskFixtures.step("task", List.of(), check));
    }

    private ChatMessage call(String id, String name, String args) {
        return ModelReplies.calls(new ToolRequest(id, name, args));
    }

    private ScriptedModelClient script(ChatMessage... replies) {
        return new ScriptedModelClient(List.of(replies));
    }

    private void executeSql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + project.store.databaseFile());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
