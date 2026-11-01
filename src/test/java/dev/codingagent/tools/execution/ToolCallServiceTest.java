package dev.codingagent.tools.execution;

import static dev.codingagent.support.ProjectFixture.readCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunState;
import dev.codingagent.run.RunStatus;
import dev.codingagent.storage.StateStore;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.tools.ToolResult;

class ToolCallServiceTest {
    @TempDir
    Path temporary;
    private ProjectFixture project;

    @BeforeEach
    void prepareProject() throws IOException {
        project = new ProjectFixture(temporary);
    }

    @Test
    void canonicalArgumentsDeduplicateWhileChangedDefinitionsFail() {
        String id = project.activeRun();
        ToolCall first = new ToolCall("call-1", "read_file", "{\"path\":\"a\",\"options\":{\"n\":1}}", false, true, "main");
        project.register(id, first);
        ToolCall equivalent = new ToolCall("call-1", "read_file", "{\"options\":{\"n\":1.0},\"path\":\"a\"}", false, true);
        project.register(id, equivalent);
        assertEquals(first, project.store.load(id).calls.get("call-1").definition());

        assertThrows(IllegalArgumentException.class,
                () -> project.register(id, new ToolCall("call-1", "read_file", "{\"path\":\"b\"}", false, true)));
        assertThrows(IllegalArgumentException.class,
                () -> project.register(id, new ToolCall("call-1", "write_file", first.argumentsJson(), true, false)));
        assertTrue(project.store.load(id).calls.get("call-1").attempts().isEmpty());
    }

    @Test
    void conflictRollsBackTheEntireRegistrationBatch() {
        String id = project.activeRun();
        project.register(id, readCall("existing"));
        assertThrows(IllegalArgumentException.class,
                () -> project.register(id, readCall("new"), new ToolCall("existing", "Bash", "{}", true, false)));
        assertFalse(project.store.load(id).calls.containsKey("new"));
    }

    @Test
    void reusedToolCallIdIsIndependentBetweenRuns() {
        String first = project.activeRun();
        String second = project.activeRun();
        project.register(first, readCall("same"));
        project.register(second, new ToolCall("same", "write_file", "{}", true, false));
        assertEquals("read_file", project.store.load(first).calls.get("same").definition().name());
        assertEquals("write_file", project.store.load(second).calls.get("same").definition().name());
    }

    @Test
    void returnsSavedResultWithoutStartingAnotherAttemptOrAppendingAnotherMessage() {
        String id = project.activeRun();
        project.register(id, readCall("call-1"));
        assertTrue(project.calls.startAttempt(id, "call-1").shouldExecute());
        ToolResult result = new ToolResult(true, "{\"content\":\"hello\"}", false);
        var finished = project.calls.finishAttempt(id, "call-1", 1, result);
        project.calls.finishAttempt(id, "call-1", 1, result);
        int messageCount = project.store.load(id).messages.size();

        StateStore reopened = new StateStore(project.config);
        ToolCallService calls = new ToolCallService(reopened);
        var repeated = calls.startAttempt(id, "call-1");
        assertFalse(repeated.shouldExecute());
        assertEquals(finished, repeated.attempt());
        RunState state = reopened.load(id);
        assertEquals(1, state.calls.get("call-1").attempts().size());
        assertEquals(messageCount, state.messages.size());
        assertEquals("call-1", state.messages.getLast().json().path("tool_call_id").asText());
        assertThrows(IllegalArgumentException.class,
                () -> calls.finishAttempt(id, "call-1", 1, new ToolResult(false, "{}", false)));
    }

    @Test
    void failedToolResultIsAlsoReplayedWithoutExecution() {
        String id = project.activeRun();
        project.register(id, readCall("bad"));
        project.calls.startAttempt(id, "bad");
        ToolResult failure = new ToolResult(false, "{\"error\":\"not found\"}", true);
        project.calls.finishAttempt(id, "bad", 1, failure);

        ToolCallService reopened = new ToolCallService(new StateStore(project.config));
        var repeated = reopened.startAttempt(id, "bad");
        assertFalse(repeated.shouldExecute());
        assertEquals(AttemptStatus.FAILED, repeated.attempt().status());
        assertEquals(failure, repeated.attempt().result());
    }

    @Test
    void recoveryMarksOnlyStartedAttemptsAndNeverStartsThemAgain() {
        String id = project.activeRun();
        project.register(id, readCall("pending"), readCall("started"), readCall("finished"));
        project.calls.startAttempt(id, "started");
        project.calls.startAttempt(id, "finished");
        project.calls.finishAttempt(id, "finished", 1, new ToolResult(true, "{}", false));

        assertEquals(1, project.calls.markInterruptedAttempts(id));
        assertEquals(0, project.calls.markInterruptedAttempts(id));
        RunState state = project.store.load(id);
        assertEquals(RunStatus.RECOVERY_REQUIRED, state.status);
        assertEquals(RunPhase.EXECUTION, state.phase);
        assertTrue(state.calls.get("pending").attempts().isEmpty());
        assertEquals(AttemptStatus.UNCERTAIN, state.calls.get("started").latestAttempt().status());
        assertThrows(IllegalStateException.class, () -> project.calls.startAttempt(id, "started"));
        assertFalse(project.calls.startAttempt(id, "finished").shouldExecute());
        assertThrows(IllegalStateException.class, () -> project.runs.changeStatus(id, RunStatus.EXECUTING));
    }

    @Test
    void doesNotCallPendingWorkUncertainAfterRestart() {
        String id = project.activeRun();
        project.register(id, readCall("pending"));
        StateStore store = new StateStore(project.config);
        ToolCallService calls = new ToolCallService(store);
        assertEquals(0, calls.markInterruptedAttempts(id));
        assertEquals(RunStatus.EXECUTING, store.load(id).status);
        assertTrue(calls.startAttempt(id, "pending").shouldExecute());
    }

    @Test
    void planningRejectsSideEffectsAndBashEvenIfMarkedReadOnly() {
        String id = project.createRun("Plan", RunPhase.PLANNING).runId;
        project.register(id, readCall("read"), new ToolCall("write", "write_file", "{}", true, false),
                new ToolCall("shell", "Bash", "{}", false, true));
        assertThrows(IllegalStateException.class, () -> project.calls.startAttempt(id, "write"));
        assertThrows(IllegalStateException.class, () -> project.calls.startAttempt(id, "shell"));
        assertTrue(project.calls.startAttempt(id, "read").shouldExecute());
        assertTrue(project.store.load(id).calls.get("write").attempts().isEmpty());
    }

    @Test
    void twoServicesCannotStartTheSameCallTwice() throws Exception {
        String id = project.activeRun();
        project.register(id, readCall("call-1"));
        ToolCallService second = new ToolCallService(new StateStore(project.config));
        Callable<Boolean> firstStart = () -> tryStart(project.calls, id);
        Callable<Boolean> secondStart = () -> tryStart(second, id);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(firstStart, secondStart));
            assertNotEquals(results.get(0).get(), results.get(1).get());
        }
        assertEquals(1, project.store.load(id).calls.get("call-1").attempts().size());
    }

    private static boolean tryStart(ToolCallService calls, String runId) {
        try {
            return calls.startAttempt(runId, "call-1").shouldExecute();
        } catch (IllegalStateException exception) {
            if (!exception.getMessage().startsWith("Recovery required:")) {
                throw exception;
            }
            return false;
        }
    }
}
