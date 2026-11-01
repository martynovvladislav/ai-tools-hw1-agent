package dev.codingagent.journal.rollback;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.codingagent.agent.Agent;
import dev.codingagent.journal.JournalConflictException;
import dev.codingagent.json.Json;
import dev.codingagent.run.RunStatus;
import dev.codingagent.storage.StateStore;
import dev.codingagent.support.JournalFixtures;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TestProcesses;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;

class RollbackTest {
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
    void restoresCreatedModifiedDeletedAndBinaryFilesAfterReopenAndKeepsUnrelatedWork() throws Exception {
        byte[] binary = {0, -1, 13, 10, 42};
        Files.write(file("a.bin"), binary);
        Files.writeString(file("b.txt"), "original\r\n");
        Files.writeString(file("keep.txt"), "initial");
        String id = JournalFixtures.start(project);
        assertTrue(JournalFixtures.execute(project, tools, id, JournalFixtures.bash("mutate",
                "printf changed > a.bin; rm -- b.txt; mkdir -p nested; printf new > nested/new.txt")).success());
        Files.writeString(file("keep.txt"), "later external content");
        Files.writeString(file("unrelated.txt"), "preserve this too");
        String budget = Json.write(project.store.load(id).budget);
        StateStore reopened = new StateStore(project.config);
        new RollbackService(reopened).rollback(id);
        assertArrayEquals(binary, Files.readAllBytes(file("a.bin")));
        assertArrayEquals("original\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), Files.readAllBytes(file("b.txt")));
        assertFalse(Files.exists(file("nested")));
        assertEquals("later external content", Files.readString(file("keep.txt")));
        assertEquals("preserve this too", Files.readString(file("unrelated.txt")));
        assertEquals(RunStatus.ROLLED_BACK, reopened.load(id).status);
        assertEquals(budget, Json.write(reopened.load(id).budget));
    }

    @ParameterizedTest
    @ValueSource(strings = {"modify", "delete", "directory"})
    void anyOwnedPathConflictPreventsAllOtherRestores(String kind) throws Exception {
        Files.writeString(file("a.txt"), "a original");
        Files.writeString(file("z.txt"), "z original");
        String id = JournalFixtures.start(project);
        JournalFixtures.execute(project, tools, id, JournalFixtures.write("a", "a.txt", "a changed"));
        JournalFixtures.execute(project, tools, id, JournalFixtures.write("z", "z.txt", "z changed"));
        if (kind.equals("modify")) {
            Files.writeString(file("z.txt"), "external");
        } else {
            Files.delete(file("z.txt"));
            if (kind.equals("directory")) {
                Files.createDirectory(file("z.txt"));
            }
        }
        assertThrows(JournalConflictException.class, () -> new RollbackService(new StateStore(project.config)).rollback(id));
        assertEquals("a changed", Files.readString(file("a.txt")));
        assertTrue(project.store.load(id).journal.rollback == null);
        if (kind.equals("modify")) {
            assertEquals("external", Files.readString(file("z.txt")));
        }
    }

    @Test
    void refusesToRemoveACreatedDirectoryContainingAnUnrelatedExternalFile() throws Exception {
        String id = JournalFixtures.start(project);
        JournalFixtures.execute(project, tools, id, JournalFixtures.bash("create", "mkdir new; printf owned > new/owned.txt"));
        Files.writeString(file("new/external.txt"), "unrelated");
        assertThrows(JournalConflictException.class, () -> new RollbackService(project.store).rollback(id));
        assertEquals("owned", Files.readString(file("new/owned.txt")));
        assertEquals("unrelated", Files.readString(file("new/external.txt")));
    }

    @Test
    void restoresFileAndDirectoryTypeReplacementsWithoutRecursiveDeletion() throws Exception {
        Files.writeString(file("a"), "original file");
        Files.createDirectory(file("d"));
        Files.writeString(file("d/b.txt"), "original child");
        String id = JournalFixtures.start(project);
        var result = JournalFixtures.execute(project, tools, id, JournalFixtures.bash("replace",
                "rm -- a; mkdir a; printf new > a/child; rm -- d/b.txt; rmdir d; printf replacement > d"));
        assertTrue(result.success(), result.output().toString());
        new RollbackService(project.store).rollback(id);
        assertEquals("original file", Files.readString(file("a")));
        assertTrue(Files.isDirectory(file("d")));
        assertEquals("original child", Files.readString(file("d/b.txt")));
    }

    @Test
    void interruptedRollbackIsDurableAndACompletedRepeatNeverOverwritesLaterEdits() throws Exception {
        Files.writeString(file("a.txt"), "a original");
        Files.writeString(file("z.txt"), "z original");
        String id = JournalFixtures.start(project);
        JournalFixtures.execute(project, tools, id, JournalFixtures.bash("change", "printf changed > a.txt; printf changed > z.txt"));
        AtomicInteger writes = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> new RollbackService(project.store, path -> {
            if (writes.incrementAndGet() == 1) {
                throw new IllegalStateException("Controlled rollback interruption");
            }
        }).rollback(id));
        assertTrue(project.store.load(id).journal.rollback.finishedAt == null);
        assertThrows(IllegalStateException.class, () -> new Agent(project.store,
                (history, definitions) -> { throw new AssertionError("Model called during rollback"); }, tools, 5).run(id));
        Path restoredFirst = Files.readString(file("a.txt")).equals("a original") ? file("a.txt") : file("z.txt");
        String restoredContent = Files.readString(restoredFirst);
        Files.writeString(restoredFirst, "external during pending rollback");
        assertThrows(JournalConflictException.class, () -> new RollbackService(project.store).rollback(id));
        assertEquals("external during pending rollback", Files.readString(restoredFirst));
        Files.writeString(restoredFirst, restoredContent);
        StateStore reopened = new StateStore(project.config);
        new RollbackService(reopened).rollback(id);
        assertEquals("a original", Files.readString(file("a.txt")));
        assertEquals("z original", Files.readString(file("z.txt")));
        Files.writeString(file("a.txt"), "external after rollback");
        new RollbackService(reopened).rollback(id);
        assertEquals("external after rollback", Files.readString(file("a.txt")));
    }

    @Test
    void aCreatedThenDeletedPathStillConflictsWithALaterExternalFile() throws Exception {
        Files.writeString(file("a.txt"), "original");
        String id = JournalFixtures.start(project);
        JournalFixtures.execute(project, tools, id, JournalFixtures.write("edit", "a.txt", "changed"));
        JournalFixtures.execute(project, tools, id, JournalFixtures.write("create", "temporary.txt", "owned"));
        JournalFixtures.execute(project, tools, id, JournalFixtures.bash("delete", "rm -- temporary.txt"));
        var change = project.store.load(id).journal.paths.get("temporary.txt");
        assertTrue(change.before() == null && change.after() == null);
        Files.writeString(file("temporary.txt"), "external");

        assertThrows(JournalConflictException.class, () -> new RollbackService(project.store).rollback(id));
        assertEquals("changed", Files.readString(file("a.txt")));
        assertEquals("external", Files.readString(file("temporary.txt")));
    }

    @Test
    void aFailedFinalSqlCommitCanBeRetriedWithoutRepeatingFileChanges() throws Exception {
        Files.writeString(file("a.txt"), "original");
        String id = JournalFixtures.start(project);
        JournalFixtures.execute(project, tools, id, JournalFixtures.write("write", "a.txt", "changed"));
        sql("CREATE TRIGGER reject_rollback AFTER UPDATE ON runs WHEN NEW.state_json LIKE '%\"status\":\"ROLLED_BACK\"%' "
                + "BEGIN SELECT RAISE(ABORT, 'fail'); END");
        assertThrows(IllegalStateException.class, () -> new RollbackService(project.store).rollback(id));
        assertEquals("original", Files.readString(file("a.txt")));
        assertTrue(project.store.load(id).journal.rollback.finishedAt == null);
        sql("DROP TRIGGER reject_rollback");
        new RollbackService(new StateStore(project.config), path -> { throw new AssertionError("Repeated restored write"); }).rollback(id);
        assertEquals(RunStatus.ROLLED_BACK, project.store.load(id).status);
    }

    private Path file(String name) {
        return project.config.workspace().resolve(name);
    }

    private void sql(String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + project.store.databaseFile());
                var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
