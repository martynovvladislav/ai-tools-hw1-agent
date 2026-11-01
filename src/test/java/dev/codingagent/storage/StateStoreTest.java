package dev.codingagent.storage;

import static dev.codingagent.support.ProjectFixture.readCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.codingagent.config.AgentConfig;
import dev.codingagent.json.Json;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.run.RunState;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.tools.ToolResult;
import dev.codingagent.tools.execution.AttemptStatus;
import dev.codingagent.tools.execution.ToolCallService;

class StateStoreTest {
    @TempDir
    Path temporary;
    private ProjectFixture project;

    @BeforeEach
    void prepareProject() throws IOException {
        project = new ProjectFixture(temporary);
    }

    @Test
    void resultAndToolMessageRollbackTogetherOnStorageFailure() throws Exception {
        String id = project.activeRun();
        project.register(id, readCall("call-1"));
        project.calls.startAttempt(id, "call-1");
        int messagesBefore = project.store.load(id).messages.size();
        executeSql("""
                CREATE TRIGGER fail_state_update AFTER UPDATE ON runs
                BEGIN SELECT RAISE(ABORT, 'controlled storage failure'); END
                """);

        assertThrows(IllegalStateException.class,
                () -> project.calls.finishAttempt(id, "call-1", 1, new ToolResult(true, "{}", false)));
        RunState unchanged = project.store.load(id);
        assertEquals(AttemptStatus.STARTED, unchanged.calls.get("call-1").latestAttempt().status());
        assertEquals(messagesBefore, unchanged.messages.size());
    }

    @Test
    void rejectsStateDirectoryWithinWorkspaceBeforeCreatingIt() {
        Path forbidden = project.config.workspace().resolve("state/nested");
        assertThrows(IllegalArgumentException.class,
                () -> new StateStore(new AgentConfig(project.config.workspace(), forbidden, "bash", "python3")));
        assertFalse(Files.exists(project.config.workspace().resolve("state")));
    }

    @Test
    void refusesToUseAnotherWorkspaceForAnExistingRun() throws IOException {
        String id = project.activeRun();
        Path other = Files.createDirectory(temporary.resolve("other-workspace"));
        StateStore wrong = new StateStore(new AgentConfig(other, project.config.stateDirectory(), "bash", "python3"));
        assertThrows(IllegalArgumentException.class, () -> wrong.load(id));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolCallService(wrong).startAttempt(id, "missing"));
    }

    @Test
    void rejectsStateDirectoryContainingWorkspace() {
        assertThrows(IllegalArgumentException.class,
                () -> new StateStore(new AgentConfig(project.config.workspace(), temporary, "bash", "python3")));
        assertFalse(Files.exists(temporary.resolve("runs.sqlite")));
    }

    @Test
    void refusesNewerJsonFormatWithoutResettingTheStoredRun() throws Exception {
        String id = project.createRun("Keep this run").runId;
        int version = RunState.CURRENT_FORMAT_VERSION + 1;
        var future = Json.parseObject(Json.write(project.store.load(id))).put("formatVersion", version);
        writeSnapshot(id, Json.write(future), false);
        assertThrows(IllegalStateException.class, () -> new StateStore(project.config).load(id));
        assertEquals(version, Json.parseObject(readSnapshot(id)).path("formatVersion").asInt());
    }

    @Test
    void keepsEverythingInOneApplicationTable() throws Exception {
        String id = project.activeRun();
        project.register(id, readCall("read"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + project.store.databaseFile());
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            List<String> tables = new ArrayList<>();
            while (result.next()) {
                tables.add(result.getString(1));
            }
            assertEquals(List.of("runs"), tables);
        }
    }

    @Test
    void callbackFailureDoesNotPersistPartialChanges() {
        String id = project.activeRun();
        String before = Json.write(project.store.load(id));
        assertThrows(IllegalStateException.class, () -> project.store.update(id, state -> {
            state.goal = "Changed in memory";
            state.messages.add(ChatMessage.user("Also changed"));
            throw new IllegalStateException("Controlled callback failure");
        }));
        assertEquals(before, Json.write(project.store.load(id)));
    }

    @Test
    void loadedStateIsDetachedAndOnlyUpdatePersistsChanges() {
        String id = project.activeRun();
        RunState detached = project.store.load(id);
        detached.goal = "Unsaved change";
        assertEquals("Fix order", project.store.load(id).goal);
        assertTrue(project.store.find("missing").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> project.store.load("missing"));
    }

    @Test
    void rejectsChangingRunIdentityInsideAnUpdate() {
        String id = project.activeRun();
        assertThrows(IllegalArgumentException.class,
                () -> project.store.update(id, state -> { state.runId = "another-id"; return null; }));
        assertEquals(id, project.store.load(id).runId);
    }

    @Test
    void refusesOldSchemaWithoutDeletingExistingData() throws Exception {
        Path oldState = Files.createDirectory(temporary.resolve("old-state"));
        Path oldDatabase = oldState.resolve("runs.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + oldDatabase);
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE runs(run_id TEXT PRIMARY KEY, goal TEXT NOT NULL)");
            statement.execute("INSERT INTO runs VALUES ('old-run', 'Keep this goal')");
        }
        assertThrows(IllegalStateException.class,
                () -> new StateStore(new AgentConfig(project.config.workspace(), oldState, "bash", "python3")));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + oldDatabase);
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT goal FROM runs WHERE run_id = 'old-run'")) {
            result.next();
            assertEquals("Keep this goal", result.getString(1));
        }
    }

    @Test
    void rejectsAnOldSnapshotWithoutChangingStoredData() throws Exception {
        String snapshot;
        try (var input = getClass().getResourceAsStream("/previous-run-state.json")) {
            snapshot = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var expected = Json.parseObject(snapshot);
        expected.put("workspace", project.store.workspace().toString());
        writeSnapshot("previous-run", Json.write(expected), true);

        assertThrows(IllegalStateException.class, () -> project.store.load("previous-run"));
        assertEquals(Json.write(expected), readSnapshot("previous-run"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"budget", "tasks", "journal"})
    void incompleteCurrentSnapshotsAreRejectedWithoutFillingInMissingState(String field) throws Exception {
        String id = project.activeRun();
        var damaged = Json.parseObject(Json.write(project.store.load(id)));
        damaged.remove(field);
        String json = Json.write(damaged);
        writeSnapshot(id, json, false);
        assertThrows(IllegalArgumentException.class, () -> new StateStore(project.config).load(id));
        assertEquals(json, readSnapshot(id));
    }

    private void executeSql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + project.store.databaseFile());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void writeSnapshot(String id, String json, boolean insert) throws Exception {
        String sql = insert ? "INSERT INTO runs(state_json, run_id) VALUES (?, ?)"
                : "UPDATE runs SET state_json = ? WHERE run_id = ?";
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + project.store.databaseFile());
                var statement = connection.prepareStatement(sql)) {
            statement.setString(1, json);
            statement.setString(2, id);
            statement.executeUpdate();
        }
    }

    private String readSnapshot(String id) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + project.store.databaseFile());
                var statement = connection.prepareStatement("SELECT state_json FROM runs WHERE run_id = ?")) {
            statement.setString(1, id);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }
}
