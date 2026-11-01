package dev.codingagent.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.codingagent.journal.rollback.RollbackService;
import dev.codingagent.run.RunStatus;
import dev.codingagent.run.recovery.RecoveryAction;
import dev.codingagent.run.recovery.RecoveryService;
import dev.codingagent.storage.StateStore;
import dev.codingagent.support.JournalFixtures;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TestProcesses;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.execution.ToolCallService;

class JournalRecoveryTest {
    @TempDir
    Path temporary;
    private ProjectFixture project;
    private ToolRegistry tools;

    @BeforeEach
    void prepare() throws Exception {
        project = new ProjectFixture(temporary, TestProcesses.bash(), TestProcesses.python());
        tools = ToolRegistry.defaults(project.config, ToolLimits.defaults());
        Files.writeString(file(), "original");
    }

    @Test
    void uncertainWriteIsNotOwnedUntilAnExplicitReviewedDecisionAndCannotBeRolledBackEarly() throws Exception {
        String id = JournalFixtures.start(project);
        JournalFixtures.unfinished(project, tools, id, JournalFixtures.write("write", "a.txt", "changed"));
        assertThrows(IllegalStateException.class, () -> new RollbackService(project.store).rollback(id));
        StateStore reopened = new StateStore(project.config);
        new ToolCallService(reopened).markInterruptedAttempts(id);
        assertEquals(JournalActionStatus.UNCERTAIN, reopened.load(id).journal.actions.getFirst().status);
        assertTrue(reopened.load(id).journal.paths.isEmpty());
        new JournalService(reopened).observePending(id);
        var action = reopened.load(id).journal.actions.getFirst();
        assertEquals(1, action.changes.size());
        assertTrue(action.observation != null);
        var recovery = new RecoveryService(reopened, tools);
        assertThrows(IllegalStateException.class, () -> recovery.resolve(id, "write", RecoveryAction.SKIP));
        assertThrows(IllegalStateException.class, () -> new RollbackService(reopened).rollback(id));
        recovery.resolve(id, "write", RecoveryAction.FAIL, ChangeAttribution.INCLUDE, action.observation);
        assertEquals(RunStatus.FAILED, reopened.load(id).status);
        assertTrue(reopened.load(id).journal.paths.containsKey("a.txt"));
        assertEquals(JournalActionStatus.RECORDED, reopened.load(id).journal.actions.getFirst().status);
        new RollbackService(reopened).rollback(id);
        assertEquals("original", Files.readString(file()));
        assertEquals(RunStatus.ROLLED_BACK, reopened.load(id).status);
    }

    @Test
    void excludedObservationIsPreservedAsExternalAndNeverRestoredByRollback() throws Exception {
        String id = JournalFixtures.start(project);
        JournalFixtures.unfinished(project, tools, id, JournalFixtures.write("write", "a.txt", "observed change"));
        project.calls.markInterruptedAttempts(id);
        new JournalService(project.store).observePending(id);
        String token = project.store.load(id).journal.actions.getFirst().observation;
        new RecoveryService(project.store, tools).resolve(id, "write", RecoveryAction.SKIP, ChangeAttribution.EXCLUDE, token);
        assertEquals(JournalActionStatus.EXCLUDED, project.store.load(id).journal.actions.getFirst().status);
        assertTrue(project.store.load(id).journal.paths.isEmpty());
        new RollbackService(new StateStore(project.config)).rollback(id);
        assertEquals("observed change", Files.readString(file()));
    }

    @Test
    void aChangedTreeInvalidatesTheObservationBeforeAnyDecisionIsApplied() throws Exception {
        String id = JournalFixtures.start(project);
        JournalFixtures.unfinished(project, tools, id, JournalFixtures.write("write", "a.txt", "first observation"));
        project.calls.markInterruptedAttempts(id);
        var journal = new JournalService(project.store);
        journal.observePending(id);
        String oldToken = project.store.load(id).journal.actions.getFirst().observation;
        Files.writeString(file(), "external change after review");
        var recovery = new RecoveryService(project.store, tools);
        assertThrows(JournalConflictException.class,
                () -> recovery.resolve(id, "write", RecoveryAction.SKIP, ChangeAttribution.INCLUDE, oldToken));
        assertTrue(project.store.load(id).decisions.isEmpty());
        assertTrue(project.store.load(id).journal.paths.isEmpty());
        assertEquals("external change after review", Files.readString(file()));
        journal.observePending(id);
        String newToken = project.store.load(id).journal.actions.getFirst().observation;
        assertNotEquals(oldToken, newToken);
        recovery.resolve(id, "write", RecoveryAction.SKIP, ChangeAttribution.EXCLUDE, newToken);
        assertEquals(ChangeAttribution.EXCLUDE, project.store.load(id).journal.actions.getFirst().attribution);
    }

    @Test
    void excludingAChangeToAnAlreadyOwnedPathProtectsItFromLaterWritesAndRollback() throws Exception {
        String id = JournalFixtures.start(project);
        JournalFixtures.execute(project, tools, id, JournalFixtures.write("confirmed", "a.txt", "confirmed change"));
        JournalFixtures.unfinished(project, tools, id, JournalFixtures.write("uncertain", "a.txt", "external according to user"));
        project.calls.markInterruptedAttempts(id);
        new JournalService(project.store).observePending(id);
        String token = project.store.load(id).journal.actions.getLast().observation;
        new RecoveryService(project.store, tools).resolve(id, "uncertain", RecoveryAction.SKIP, ChangeAttribution.EXCLUDE, token);
        var next = JournalFixtures.write("next", "a.txt", "must not overwrite");
        project.register(id, tools.describe(next));
        assertThrows(JournalConflictException.class, () -> project.calls.startAttempt(id, "next"));
        assertThrows(JournalConflictException.class, () -> new RollbackService(project.store).rollback(id));
        assertEquals("external according to user", Files.readString(file()));
        assertEquals("confirmed change", new String(project.store.load(id).journal.paths.get("a.txt").after().bytes(),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private Path file() {
        return project.config.workspace().resolve("a.txt");
    }
}
