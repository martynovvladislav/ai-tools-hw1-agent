package dev.codingagent.journal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.codingagent.journal.diff.DiffRenderer;
import dev.codingagent.journal.snapshot.WorkspaceTree;
import dev.codingagent.storage.StateStore;
import dev.codingagent.support.JournalFixtures;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TestProcesses;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.execution.AttemptStatus;

class JournalTest {
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
    void storesExactImagesAndKeepsFirstBeforeAndLastAfterAcrossWrites() throws Exception {
        Files.writeString(file("a.txt"), "original\r\n");
        Files.createDirectory(file(".generated"));
        byte[] binary = {0, -1, 10, 42};
        Files.write(file(".generated/data.bin"), binary);
        String id = JournalFixtures.start(project);
        var baseline = project.store.load(id).journal.expectedTree;
        assertArrayEquals(binary, baseline.paths().get(".generated/data.bin").bytes());
        assertTrue(baseline.paths().get(".generated").directory());
        JournalFixtures.execute(project, tools, id, JournalFixtures.write("first", "a.txt", "first change\n"));
        JournalFixtures.execute(project, tools, id, JournalFixtures.write("last", "a.txt", "last change\n"));
        var restored = new StateStore(project.config).load(id).journal;
        assertEquals(2, restored.actions.size());
        var change = restored.paths.get("a.txt");
        assertArrayEquals("original\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), change.before().bytes());
        assertArrayEquals("last change\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), change.after().bytes());
        assertEquals(restored.actions.get(0).afterTree, restored.actions.get(1).beforeTree);
        String diff = DiffRenderer.render(restored.paths.values());
        assertTrue(diff.contains("-original"));
        assertTrue(diff.contains("+last change"));
        assertTrue(diff.contains(change.before().sha256()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"modify", "create", "delete", "directory"})
    void outsideTreeChangesStopTheNextSideEffectBeforeStarted(String kind) throws Exception {
        Files.writeString(file("a.txt"), "original");
        String id = JournalFixtures.start(project);
        switch (kind) {
            case "modify" -> Files.writeString(file("a.txt"), "external");
            case "create" -> Files.writeString(file("external.txt"), "external");
            case "delete" -> Files.delete(file("a.txt"));
            case "directory" -> Files.createDirectory(file("external-directory"));
            default -> throw new AssertionError(kind);
        }
        var request = JournalFixtures.write("blocked", "other.txt", "must not write");
        project.register(id, tools.describe(request));
        assertThrows(JournalConflictException.class, () -> project.calls.startAttempt(id, request.id()));
        assertTrue(project.store.load(id).calls.get("blocked").attempts().isEmpty());
        assertTrue(project.store.load(id).journal.actions.isEmpty());
        assertTrue(Files.notExists(file("other.txt")));
    }

    @Test
    void arbitraryBashCreationModificationAndDeletionAreAllRecorded() throws Exception {
        Files.writeString(file("a.txt"), "before");
        Files.writeString(file("b.txt"), "delete me");
        String id = JournalFixtures.start(project);
        var result = JournalFixtures.execute(project, tools, id, JournalFixtures.bash("shell",
                "mkdir -p nested; printf changed > a.txt; printf new > nested/new.txt; rm -- b.txt"));
        assertTrue(result.success(), result.output().toString());
        var journal = project.store.load(id).journal;
        assertEquals(Set.of("a.txt", "b.txt", "nested", "nested/new.txt"), journal.paths.keySet());
        assertEquals(JournalActionStatus.RECORDED, journal.actions.getFirst().status);
        assertEquals(4, journal.actions.getFirst().changes.size());
        assertTrue(journal.paths.get("b.txt").after() == null);
        assertTrue(journal.paths.get("nested/new.txt").before() == null);
    }

    @Test
    void failedBashStillRecordsItsActualFileChanges() throws Exception {
        Files.writeString(file("a.txt"), "before");
        String id = JournalFixtures.start(project);
        var result = JournalFixtures.execute(project, tools, id,
                JournalFixtures.bash("failed-shell", "printf partial > a.txt; exit 7"));
        assertTrue(!result.success());
        assertEquals("partial", Files.readString(file("a.txt")));
        assertEquals(JournalActionStatus.RECORDED, project.store.load(id).journal.actions.getFirst().status);
        assertTrue(project.store.load(id).journal.paths.containsKey("a.txt"));
    }

    @Test
    void resultAndAfterSnapshotRollbackTogetherWhenSqlWriteFails() throws Exception {
        Files.writeString(file("a.txt"), "before");
        String id = JournalFixtures.start(project);
        var result = JournalFixtures.unfinished(project, tools, id, JournalFixtures.write("write", "a.txt", "after"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + project.store.databaseFile());
                var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_update AFTER UPDATE ON runs BEGIN SELECT RAISE(ABORT, 'fail'); END");
        }
        assertThrows(IllegalStateException.class, () -> project.calls.finishAttempt(id, "write", 1, result));
        var state = project.store.load(id);
        assertEquals("after", Files.readString(file("a.txt")));
        assertEquals(AttemptStatus.STARTED, state.calls.get("write").latestAttempt().status());
        assertEquals(JournalActionStatus.STARTED, state.journal.actions.getFirst().status);
        assertTrue(state.journal.actions.getFirst().afterTree == null);
        assertTrue(state.journal.paths.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.txt", "/absolute", "C:/outside", "a//b", "a\\b"})
    void journalPathsCannotEscapeTheWorkspace(String path) throws Exception {
        var workspace = new WorkspaceTree(project.config.workspace());
        assertThrows(IOException.class, () -> workspace.resolve(path));
    }

    private Path file(String name) {
        return project.config.workspace().resolve(name);
    }
}
