package dev.codingagent.run.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.codingagent.agent.Agent;
import dev.codingagent.agent.ExecutionHook;
import dev.codingagent.journal.ChangeAttribution;
import dev.codingagent.journal.JournalService;
import dev.codingagent.json.Json;
import dev.codingagent.model.ModelClient;
import dev.codingagent.model.ScriptedModelClient;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.run.RunStatus;
import dev.codingagent.storage.StateStore;
import dev.codingagent.support.ModelReplies;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.execution.AttemptStatus;
import dev.codingagent.tools.execution.ToolCall;
import dev.codingagent.tools.execution.ToolCallService;

class RecoveryTest {
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
    void crashAfterRealWriteRequiresAChoiceAndSkipDoesNotRepeatTheWrite() throws Exception {
        var write = new ToolRequest("write", "write_file", "{\"path\":\"result.txt\",\"content\":\"from agent\"}");
        var model = new ScriptedModelClient(List.of(ModelReplies.calls(write),
                ModelReplies.calls(new ToolRequest("verify", "read_file", "{\"path\":\"result.txt\"}")),
                ModelReplies.text("Continued after skip")));
        String id = project.createRun("Write a file").runId;
        assertThrows(IOException.class, () -> crashingAgent(model).run(id));
        Path result = project.config.workspace().resolve("result.txt");
        assertEquals("from agent", Files.readString(result));

        StateStore reopened = new StateStore(project.config);
        var calls = new ToolCallService(reopened);
        assertEquals(1, calls.markInterruptedAttempts(id));
        assertEquals(AttemptStatus.UNCERTAIN, reopened.load(id).calls.get("write").latestAttempt().status());
        assertThrows(IllegalStateException.class, () -> new Agent(reopened, model, tools, 5).run(id));
        assertThrows(IllegalStateException.class,
                () -> new RecoveryService(reopened, tools).resolve(id, "write", RecoveryAction.RETRY));
        assertTrue(reopened.load(id).decisions.isEmpty());

        Files.writeString(result, "later external content");
        new JournalService(reopened).observePending(id);
        String observation = reopened.load(id).journal.actions.getLast().observation;
        new RecoveryService(reopened, tools).resolve(id, "write", RecoveryAction.SKIP,
                ChangeAttribution.EXCLUDE, observation);
        assertEquals("Continued after skip", new Agent(reopened, model, tools, 5).run(id));
        assertEquals("later external content", Files.readString(result));
        assertEquals(1, reopened.load(id).calls.get("write").attempts().size());
        assertFalse(reopened.load(id).calls.get("write").latestAttempt().result().success());
        assertEquals("RECOVERY_SKIP", reopened.load(id).decisions.getFirst().kind());
    }

    @Test
    void finishedAndNotYetStartedCallsRemainDistinctAfterABatchCrash() throws Exception {
        var finished = new ToolRequest("finished", "write_file", "{\"path\":\"first.txt\",\"content\":\"first\"}");
        var uncertain = new ToolRequest("uncertain", "write_file", "{\"path\":\"second.txt\",\"content\":\"second\"}");
        var pending = new ToolRequest("pending", "write_file", "{\"path\":\"third.txt\",\"content\":\"third\"}");
        var model = new ScriptedModelClient(List.of(ModelReplies.calls(finished, uncertain, pending), ModelReplies.text("Done")));
        String id = project.createRun("Write three files").runId;
        Agent crashing = new Agent(project.store, model, tools, 5, (call, result) -> {
            if (call.toolCallId().equals("uncertain")) {
                throw new IOException("Crash after second file");
            }
        });
        assertThrows(IOException.class, () -> crashing.run(id));
        Path first = project.config.workspace().resolve("first.txt");
        Path second = project.config.workspace().resolve("second.txt");
        Path third = project.config.workspace().resolve("third.txt");
        assertEquals("first", Files.readString(first));
        assertEquals("second", Files.readString(second));
        assertFalse(Files.exists(third));
        StateStore reopened = new StateStore(project.config);
        assertEquals(1, new ToolCallService(reopened).markInterruptedAttempts(id));
        assertTrue(reopened.load(id).calls.get("pending").attempts().isEmpty());
        new JournalService(reopened).observePending(id);
        String observation = reopened.load(id).journal.actions.getLast().observation;
        new RecoveryService(reopened, tools).resolve(id, "uncertain", RecoveryAction.SKIP,
                ChangeAttribution.INCLUDE, observation);
        new Agent(reopened, model, tools, 5).run(id);
        assertEquals("first", Files.readString(first));
        assertEquals("third", Files.readString(third));
        assertEquals(1, reopened.load(id).calls.get("finished").attempts().size());
        assertEquals(RunStatus.COMPLETED, reopened.load(id).status);
    }

    @Test
    void safeRetryIsDurableAndCreatesANewAttemptWithoutResettingHistory() throws Exception {
        Files.writeString(project.config.workspace().resolve("file.txt"), "before crash");
        var read = new ToolRequest("read", "read_file", "{\"path\":\"file.txt\"}");
        var model = new ScriptedModelClient(List.of(ModelReplies.calls(read), ModelReplies.text("Read again")));
        String id = project.createRun("Read a file").runId;
        assertThrows(IOException.class, () -> crashingAgent(model).run(id));
        project.calls.markInterruptedAttempts(id);
        new RecoveryService(project.store, tools).resolve(id, "read", RecoveryAction.RETRY);

        StateStore reopened = new StateStore(project.config);
        var before = reopened.load(id);
        assertEquals(RunStatus.EXECUTING, before.status);
        assertEquals(2, before.calls.get("read").attempts().size());
        assertEquals(AttemptStatus.UNCERTAIN, before.calls.get("read").attempts().getFirst().status());
        assertEquals(AttemptStatus.READY, before.calls.get("read").latestAttempt().status());
        assertEquals(0, new ToolCallService(reopened).markInterruptedAttempts(id));
        Files.writeString(project.config.workspace().resolve("file.txt"), "after restart");
        assertEquals("Read again", new Agent(reopened, model, tools, 5).run(id));
        var after = reopened.load(id);
        assertEquals(2, after.calls.get("read").latestAttempt().number());
        assertTrue(after.calls.get("read").latestAttempt().result().output().toString().contains("after restart"));
        assertEquals(2, after.messages.stream().filter(message -> message.role().equals("assistant")).count());
        assertEquals("RECOVERY_RETRY", after.decisions.getFirst().kind());
    }

    @Test
    void explicitFailStopsTheRunWithoutExecutingPendingWork() throws Exception {
        String id = uncertainRead("read");
        project.register(id, ProjectFixture.readCall("pending"));
        new RecoveryService(project.store, tools).resolve(id, "read", RecoveryAction.FAIL);
        assertEquals(RunStatus.FAILED, project.store.load(id).status);
        assertTrue(project.store.load(id).calls.get("pending").attempts().isEmpty());
        ModelClient never = (history, definitions) -> { throw new AssertionError("Failed run called model"); };
        assertThrows(IllegalStateException.class, () -> new Agent(project.store, never, tools, 5).run(id));
        assertEquals("RECOVERY_FAIL", project.store.load(id).decisions.getFirst().kind());
    }

    @Test
    void allUncertainCallsMustBeResolvedBeforeAnApprovedRetryCanStart() {
        String id = project.activeRun();
        project.register(id, ProjectFixture.readCall("first"), ProjectFixture.readCall("second"));
        project.calls.startAttempt(id, "first");
        project.calls.startAttempt(id, "second");
        project.calls.markInterruptedAttempts(id);
        var recovery = new RecoveryService(project.store, tools);
        recovery.resolve(id, "first", RecoveryAction.RETRY);
        assertEquals(RunStatus.RECOVERY_REQUIRED, project.store.load(id).status);
        assertThrows(IllegalStateException.class, () -> project.calls.startAttempt(id, "first"));
        recovery.resolve(id, "second", RecoveryAction.SKIP);
        assertEquals(RunStatus.EXECUTING, project.store.load(id).status);
        assertTrue(project.calls.startAttempt(id, "first").shouldExecute());
    }

    @Test
    void decisionAndItsAppliedStateRollbackTogetherOnSqlFailure() throws Exception {
        String id = uncertainRead("read");
        String before = Json.write(project.store.load(id));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + project.store.databaseFile());
                var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_update AFTER UPDATE ON runs "
                    + "BEGIN SELECT RAISE(ABORT, 'controlled failure'); END");
        }
        assertThrows(IllegalStateException.class,
                () -> new RecoveryService(project.store, tools).resolve(id, "read", RecoveryAction.RETRY));
        assertEquals(before, Json.write(project.store.load(id)));
    }

    @Test
    void arbitraryBashIsNotRetrySafeEvenWithForgedSavedMetadata() {
        String id = project.activeRun();
        project.register(id, new ToolCall("shell", "Bash", "{}", true, true));
        project.calls.startAttempt(id, "shell");
        project.calls.markInterruptedAttempts(id);
        assertThrows(IllegalStateException.class,
                () -> new RecoveryService(project.store, tools).resolve(id, "shell", RecoveryAction.RETRY));
        assertTrue(project.store.load(id).decisions.isEmpty());
    }

    private Agent crashingAgent(ModelClient model) {
        ExecutionHook crash = (call, result) -> { throw new IOException("Controlled crash after tool execution"); };
        return new Agent(project.store, model, tools, 5, crash);
    }

    private String uncertainRead(String callId) {
        String id = project.activeRun();
        project.register(id, ProjectFixture.readCall(callId));
        project.calls.startAttempt(id, callId);
        project.calls.markInterruptedAttempts(id);
        return id;
    }
}
