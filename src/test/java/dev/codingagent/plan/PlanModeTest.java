package dev.codingagent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.codingagent.agent.Agent;
import dev.codingagent.json.Json;
import dev.codingagent.model.ModelClient;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.model.ScriptedModelClient;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunStatus;
import dev.codingagent.run.recovery.RecoveryAction;
import dev.codingagent.run.recovery.RecoveryService;
import dev.codingagent.storage.StateStore;
import dev.codingagent.support.ModelReplies;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TestPlans;
import dev.codingagent.support.TaskFixtures;
import dev.codingagent.task.TaskCheck;
import dev.codingagent.support.TestProcesses;
import dev.codingagent.tools.Tool;
import dev.codingagent.tools.ToolDefinition;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.ToolResult;
import dev.codingagent.tools.execution.ToolCallService;

class PlanModeTest {
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
    void planningRejectsWriteAndEveryBashCommandWhileReadToolsStillWork() throws Exception {
        Path original = project.config.workspace().resolve("code.txt");
        Files.writeString(original, "original");
        var requests = ModelReplies.calls(
                new ToolRequest("write", "write_file", "{\"path\":\"code.txt\",\"content\":\"changed\"}"),
                new ToolRequest("shell", "Bash", "{\"command\":\"printf changed > touched.txt\",\"cwd\":\".\"}"),
                new ToolRequest("read", "read_file", "{\"path\":\"code.txt\"}"));
        var script = new ScriptedModelClient(List.of(requests, ModelReplies.text(TestPlans.JSON)));
        ModelClient checked = (history, definitions) -> {
            if (!definitions.isEmpty()) {
                assertEquals(List.of("list_files", "read_file"), definitions.stream().map(tool -> tool.name()).toList());
            }
            return script.generate(history, definitions);
        };
        String id = project.createRun("Plan the fix", RunPhase.PLANNING).runId;
        new Agent(project.store, checked, tools, 5).run(id);
        var state = new StateStore(project.config).load(id);
        assertEquals(RunStatus.AWAITING_APPROVAL, state.status);
        assertEquals("original", Files.readString(original));
        assertFalse(Files.exists(project.config.workspace().resolve("touched.txt")));
        assertFalse(state.calls.get("write").latestAttempt().result().success());
        assertFalse(state.calls.get("shell").latestAttempt().result().success());
        assertTrue(state.calls.get("read").latestAttempt().result().success());
        assertEquals(WorkPlan.parse(TestPlans.JSON), state.plan);
    }

    @Test
    void executionAdvertisesOnlyToolsForTheCurrentApprovedTask() throws Exception {
        Files.writeString(project.config.workspace().resolve("notes.txt"), "notes");
        String id = project.createRun("Inspect the notes", RunPhase.PLANNING).runId;
        var plan = TaskFixtures.plan(TaskFixtures.step("inspect", List.of(), new TaskCheck("read_file", "notes.txt", null)));
        var plans = new PlanService(project.store);
        plans.saveDraft(id, Json.write(plan));
        plans.approve(id);
        var script = new ScriptedModelClient(List.of(
                ModelReplies.calls(new ToolRequest("read-notes", "read_file", "{\"path\":\"notes.txt\"}")),
                ModelReplies.text("Inspected notes")));
        ModelClient model = (history, definitions) -> {
            assertEquals(List.of("read_file"), definitions.stream().map(ToolDefinition::name).toList());
            return script.generate(history, definitions);
        };
        new Agent(project.store, model, tools, 5).run(id);
        var state = project.store.load(id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertEquals(0, state.budget.sideEffects);
        assertEquals("notes", Files.readString(project.config.workspace().resolve("notes.txt")));
    }

    @Test
    void structuredDraftResumesAfterApiFailureWithoutRepeatingInspection() throws Exception {
        Path source = project.config.workspace().resolve("notes.txt");
        Files.writeString(source, "original");
        AtomicInteger inspectionRequests = new AtomicInteger();
        AtomicInteger draftRequests = new AtomicInteger();
        ModelClient model = new ModelClient() {
            @Override
            public ChatMessage generate(List<ChatMessage> history,
                    List<ToolDefinition> definitions) {
                assertEquals(List.of("list_files", "read_file"), definitions.stream().map(ToolDefinition::name).toList());
                if (inspectionRequests.incrementAndGet() == 1) {
                    return ModelReplies.calls(new ToolRequest("inspect-notes", "read_file", "{\"path\":\"notes.txt\"}"));
                }
                return ModelReplies.text("The file was inspected. I can now plan the change and verification.");
            }

            @Override
            public ChatMessage generateStructured(List<ChatMessage> history,
                    ObjectNode schema) throws IOException {
                assertTrue(schema.path("properties").has("steps"));
                assertTrue(history.stream().anyMatch(message -> message.role().equals("tool")
                        && message.json().path("tool_call_id").asText().equals("inspect-notes")));
                if (draftRequests.incrementAndGet() == 1) {
                    throw new IOException("Temporary API failure while drafting");
                }
                return ModelReplies.text(TestPlans.JSON);
            }
        };
        String id = project.createRun("Plan a change", RunPhase.PLANNING).runId;
        assertThrows(IOException.class, () -> new Agent(project.store, model, tools, 5).run(id));
        StateStore reopened = new StateStore(project.config);
        assertTrue(reopened.load(id).planDraftRequested);
        new Agent(reopened, model, tools, 5).run(id);
        var state = reopened.load(id);
        assertEquals(RunStatus.AWAITING_APPROVAL, state.status);
        assertFalse(state.planDraftRequested);
        assertEquals(2, inspectionRequests.get());
        assertEquals(2, draftRequests.get());
        assertEquals(4, state.budget.modelRequests);
        assertEquals(1, state.calls.size());
        assertEquals(0, state.budget.sideEffects);
        assertEquals("original", Files.readString(source));
    }

    @Test
    void bashIsBlockedEvenIfAnImplementationIsMarkedReadOnly() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        Tool misleadingBash = new Tool() {
            public ToolDefinition definition() {
                return new ToolDefinition("Bash", "Misleading metadata", List.of(), false, true);
            }

            public ToolResult execute(ObjectNode arguments, int outputBytes) {
                executions.incrementAndGet();
                return new ToolResult(true, "{}", false);
            }
        };
        var registry = new ToolRegistry(List.of(misleadingBash));
        assertTrue(registry.planningDefinitions().isEmpty());
        var model = new ScriptedModelClient(List.of(ModelReplies.calls(new ToolRequest("shell", "Bash", "{}")),
                ModelReplies.text(TestPlans.JSON)));
        String id = project.createRun("Plan", RunPhase.PLANNING).runId;
        new Agent(project.store, model, registry, 5).run(id);
        assertEquals(0, executions.get());
        assertEquals(RunStatus.AWAITING_APPROVAL, project.store.load(id).status);
    }

    @Test
    void restartWaitsForExplicitApprovalAndThenContinuesTheSameHistory() throws Exception {
        var write = ModelReplies.calls(new ToolRequest("execute", "write_file",
                "{\"path\":\"result.txt\",\"content\":\"approved execution\"}"));
        var model = new ScriptedModelClient(List.of(ModelReplies.text(TestPlans.JSON), write, ModelReplies.text("Done")));
        String id = project.createRun("Plan first", RunPhase.PLANNING).runId;
        StateStore restartedBeforePlanning = new StateStore(project.config);
        assertEquals(RunPhase.PLANNING, restartedBeforePlanning.load(id).phase);
        new Agent(restartedBeforePlanning, model, tools, 5).run(id);
        StateStore restartedBeforeApproval = new StateStore(project.config);
        assertThrows(IllegalStateException.class, () -> new Agent(restartedBeforeApproval, model, tools, 5).run(id));
        assertFalse(Files.exists(project.config.workspace().resolve("result.txt")));

        new PlanService(restartedBeforeApproval).approve(id);
        StateStore restartedAfterApproval = new StateStore(project.config);
        assertEquals(RunStatus.EXECUTING, restartedAfterApproval.load(id).status);
        assertEquals(RunPhase.EXECUTION, restartedAfterApproval.load(id).phase);
        assertEquals("APPROVE_PLAN", restartedAfterApproval.load(id).decisions.getFirst().kind());
        assertTrue(restartedAfterApproval.load(id).messages.getLast().json().path("content").asText()
                .contains("Plan Mode has ended"));
        assertEquals("Done", new Agent(restartedAfterApproval, model, tools, 5).run(id));
        assertEquals("approved execution", Files.readString(project.config.workspace().resolve("result.txt")));
        assertEquals(3, restartedAfterApproval.load(id).messages.stream()
                .filter(message -> message.role().equals("assistant")).count());
        assertThrows(IllegalStateException.class, () -> new PlanService(restartedAfterApproval).approve(id));
    }

    @Test
    void rejectedPlanRemainsSavedAndCannotBeExecuted() {
        String id = waitingPlan();
        new PlanService(project.store).reject(id);
        var state = project.store.load(id);
        assertEquals(RunStatus.FAILED, state.status);
        assertEquals(WorkPlan.parse(TestPlans.JSON), state.plan);
        assertEquals("REJECT_PLAN", state.decisions.getFirst().kind());
        ModelClient never = (history, definitions) -> { throw new AssertionError("Rejected plan called model"); };
        assertThrows(IllegalStateException.class, () -> new Agent(project.store, never, tools, 5).run(id));
    }

    @Test
    void malformedPlanIsReturnedForCorrectionInsteadOfBeingApproved() throws Exception {
        var model = new ScriptedModelClient(List.of(ModelReplies.text("I will just fix it"), ModelReplies.text(TestPlans.JSON)));
        String id = project.createRun("Plan", RunPhase.PLANNING).runId;
        new Agent(project.store, model, tools, 5).run(id);
        var state = project.store.load(id);
        assertEquals(RunStatus.AWAITING_APPROVAL, state.status);
        assertTrue(state.messages.stream().anyMatch(message -> message.json().toString().contains("Invalid plan:")));
        assertEquals(WorkPlan.parse(TestPlans.JSON), state.plan);
    }

    @Test
    void incompleteStructuredPlanIsRejected() {
        var missingCriterion = Json.parseObject(TestPlans.JSON);
        missingCriterion.remove("done_when");
        assertThrows(IllegalArgumentException.class, () -> WorkPlan.parse(Json.write(missingCriterion)));
        missingCriterion.put("done_when", "Tests pass");
        missingCriterion.putArray("steps");
        assertThrows(IllegalArgumentException.class, () -> WorkPlan.parse(Json.write(missingCriterion)));
        assertThrows(IllegalStateException.class, () -> new PlanService(project.store).approve(project.activeRun()));
    }

    @Test
    void crashDuringReadOnlyPlanningReturnsToPlanningAfterRecovery() throws Exception {
        Files.writeString(project.config.workspace().resolve("code.txt"), "original");
        var read = ModelReplies.calls(new ToolRequest("read", "read_file", "{\"path\":\"code.txt\"}"));
        var model = new ScriptedModelClient(List.of(read, ModelReplies.text(TestPlans.JSON)));
        String id = project.createRun("Plan", RunPhase.PLANNING).runId;
        Agent crashing = new Agent(project.store, model, tools, 5,
                (call, result) -> { throw new IOException("Planning crash"); });
        assertThrows(IOException.class, () -> crashing.run(id));
        StateStore reopened = new StateStore(project.config);
        new ToolCallService(reopened).markInterruptedAttempts(id);
        new RecoveryService(reopened, tools).resolve(id, "read", RecoveryAction.RETRY);
        assertEquals(RunStatus.PLANNING, reopened.load(id).status);
        assertEquals(RunPhase.PLANNING, reopened.load(id).phase);
        new Agent(reopened, model, tools, 5).run(id);
        assertEquals(RunStatus.AWAITING_APPROVAL, reopened.load(id).status);
        assertTrue(reopened.load(id).decisions.stream().noneMatch(decision -> decision.kind().equals("APPROVE_PLAN")));
    }

    @Test
    void approvalAndItsPermissionRollbackTogetherOnSqlFailure() throws Exception {
        String id = waitingPlan();
        String before = Json.write(project.store.load(id));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + project.store.databaseFile());
                var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_update AFTER UPDATE ON runs "
                    + "BEGIN SELECT RAISE(ABORT, 'controlled failure'); END");
        }
        assertThrows(IllegalStateException.class, () -> new PlanService(project.store).approve(id));
        assertEquals(before, Json.write(project.store.load(id)));
    }

    private String waitingPlan() {
        String id = project.createRun("Plan", RunPhase.PLANNING).runId;
        var plans = new PlanService(project.store);
        plans.saveDraft(id, TestPlans.JSON);
        return id;
    }
}
