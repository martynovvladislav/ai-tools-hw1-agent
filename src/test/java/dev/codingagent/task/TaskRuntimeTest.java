package dev.codingagent.task;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.codingagent.agent.Agent;
import dev.codingagent.agent.ExecutionHook;
import dev.codingagent.journal.ChangeAttribution;
import dev.codingagent.journal.JournalConflictException;
import dev.codingagent.journal.JournalService;
import dev.codingagent.json.Json;
import dev.codingagent.model.ScriptedModelClient;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.plan.PlanService;
import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunStatus;
import dev.codingagent.run.recovery.RecoveryAction;
import dev.codingagent.run.recovery.RecoveryService;
import dev.codingagent.storage.StateStore;
import dev.codingagent.support.ModelReplies;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TaskFixtures;
import dev.codingagent.support.TestProcesses;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.execution.ToolCallService;

class TaskRuntimeTest {
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
    void threeDependentTasksKeepActualEvidenceAndResumeNeverRepeatsCompletedTasks() throws Exception {
        byte[] tests = Files.readAllBytes(Path.of("workspace/test_order.py"));
        for (String file : List.of("order.py", "test_order.py")) {
            Files.copy(Path.of("workspace", file), project.config.workspace().resolve(file));
        }
        var script = ScriptedModelClient.fromFile(Path.of("examples/order-plan-responses.json"));
        String id = project.createRun("Fix the order", RunPhase.PLANNING).runId;
        new Agent(project.store, script, tools, 20).run(id);
        var planned = project.store.load(id);
        assertEquals(3, planned.tasks.size());
        assertTrue(planned.tasks.stream().allMatch(task -> task.status == TaskStatus.PENDING));
        assertTrue(planned.calls.values().stream().allMatch(call -> call.definition().taskId() == null));
        new PlanService(project.store).approve(id);

        Agent stoppedBetweenTasks = new Agent(project.store, script, tools, 20, ExecutionHook.NONE, state -> {
            if (state.tasks.get(1).status == TaskStatus.COMPLETED && state.tasks.get(2).status == TaskStatus.PENDING) {
                throw new IllegalStateException("Controlled stop between committed tasks");
            }
        });
        assertThrows(IllegalStateException.class, () -> stoppedBetweenTasks.run(id));
        var stopped = project.store.load(id);
        assertEquals(TaskStatus.COMPLETED, stopped.tasks.get(0).status);
        assertEquals(TaskStatus.COMPLETED, stopped.tasks.get(1).status);
        assertEquals(TaskStatus.PENDING, stopped.tasks.get(2).status);
        assertEquals(null, stopped.currentTaskId);
        assertFalse(stopped.calls.containsKey("run-tests"));
        String completedSnapshot = Json.write(stopped.tasks.subList(0, 2));
        Path code = project.config.workspace().resolve("order.py");
        String recordedCode = Files.readString(code);
        Files.writeString(code, recordedCode + "\n# external change\n");

        StateStore reopened = new StateStore(project.config);
        assertThrows(JournalConflictException.class,
                () -> new Agent(reopened, script, tools, 20).run(id));
        assertEquals(recordedCode + "\n# external change\n", Files.readString(code));
        assertEquals(completedSnapshot, Json.write(reopened.load(id).tasks.subList(0, 2)));
        Files.writeString(code, recordedCode);
        new Agent(reopened, ScriptedModelClient.fromFile(Path.of("examples/order-plan-responses.json")), tools, 20).run(id);
        var finished = reopened.load(id);
        assertEquals(RunStatus.COMPLETED, finished.status);
        assertTrue(finished.tasks.stream().allMatch(task -> task.status == TaskStatus.COMPLETED && !task.evidence.isEmpty()));
        assertEquals(completedSnapshot, Json.write(finished.tasks.subList(0, 2)));
        assertEquals(recordedCode, Files.readString(code));
        assertEquals("inspect", finished.calls.get("read-code").definition().taskId());
        assertEquals("fix", finished.calls.get("fix-code").definition().taskId());
        assertEquals("fix", finished.calls.get("confirm-fix").definition().taskId());
        assertEquals("verify", finished.calls.get("run-tests").definition().taskId());
        assertTrue(finished.calls.values().stream().allMatch(call -> call.attempts().size() == 1));
        var proof = finished.tasks.get(2).evidence.getFirst();
        var output = proof.result().output();
        assertEquals("run-tests", proof.toolCallId());
        assertEquals(0, output.path("exit_code").asInt());
        assertTrue((output.path("stdout").asText() + output.path("stderr").asText()).contains("Ran 2 tests"));
        assertEquals(9, finished.budget.modelRequests);
        assertEquals(8, finished.budget.toolCalls);
        assertEquals(2, finished.budget.sideEffects);
        assertArrayEquals(tests, Files.readAllBytes(project.config.workspace().resolve("test_order.py")));
        assertArrayEquals(tests, Files.readAllBytes(Path.of("workspace/test_order.py")));
        assertFalse(Files.exists(project.config.workspace().resolve("__pycache__")));
    }

    @Test
    void safeRetryKeepsTheCurrentTaskAndStoresTheActualAttemptNumberInEvidence() throws Exception {
        Files.writeString(project.config.workspace().resolve("a.txt"), "content");
        String id = TaskFixtures.approvedRun(project,
                TaskFixtures.step("read", List.of(), new TaskCheck("read_file", "a.txt", "content")));
        var model = new ScriptedModelClient(List.of(ModelReplies.calls(new ToolRequest("read-call", "read_file",
                "{\"path\":\"a.txt\"}")), ModelReplies.text("Read verified")));
        assertThrows(IOException.class, () -> new Agent(project.store, model, tools, 5,
                (call, result) -> { throw new IOException("Crash after read"); }).run(id));
        StateStore reopened = new StateStore(project.config);
        new ToolCallService(reopened).markInterruptedAttempts(id);
        new RecoveryService(reopened, tools).resolve(id, "read-call", RecoveryAction.RETRY);
        assertEquals("read", reopened.load(id).currentTaskId);
        assertEquals(TaskStatus.RUNNING, reopened.load(id).tasks.getFirst().status);
        new Agent(reopened, model, tools, 5).run(id);
        var finished = reopened.load(id);
        assertEquals(RunStatus.COMPLETED, finished.status);
        assertEquals("read", finished.calls.get("read-call").definition().taskId());
        assertEquals(2, finished.tasks.getFirst().evidence.getFirst().attempt());
        assertEquals(2, finished.budget.toolCalls);
    }

    @Test
    void failRecoveryMarksTheCurrentTaskFailedAndNeverStartsItsDependent() throws Exception {
        var first = TaskFixtures.step("write", List.of(), new TaskCheck("write_file", "a.txt", null));
        var second = TaskFixtures.step("verify", List.of("write"), new TaskCheck("read_file", "a.txt", null));
        String id = TaskFixtures.approvedRun(project, first, second);
        var model = new ScriptedModelClient(List.of(ModelReplies.calls(new ToolRequest("write-call", "write_file",
                "{\"path\":\"a.txt\",\"content\":\"changed\"}")), ModelReplies.text("Must not finish")));
        assertThrows(IOException.class, () -> new Agent(project.store, model, tools, 5,
                (call, result) -> { throw new IOException("Crash after write"); }).run(id));
        StateStore reopened = new StateStore(project.config);
        new ToolCallService(reopened).markInterruptedAttempts(id);
        new JournalService(reopened).observePending(id);
        String observation = reopened.load(id).journal.actions.getLast().observation;
        new RecoveryService(reopened, tools).resolve(id, "write-call", RecoveryAction.FAIL,
                ChangeAttribution.INCLUDE, observation);
        var failed = reopened.load(id);
        assertEquals(RunStatus.FAILED, failed.status);
        assertEquals(TaskStatus.FAILED, failed.tasks.getFirst().status);
        assertEquals(TaskStatus.PENDING, failed.tasks.get(1).status);
        assertTrue(failed.tasks.getFirst().evidence.isEmpty());
        assertThrows(IllegalStateException.class, () -> new Agent(reopened, model, tools, 5).run(id));
        assertEquals(1, failed.calls.size());
    }
}
