package dev.codingagent.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.codingagent.agent.Agent;
import dev.codingagent.model.ModelClient;
import dev.codingagent.model.ScriptedModelClient;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.plan.PlanService;
import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunStatus;
import dev.codingagent.storage.StateStore;
import dev.codingagent.support.ModelReplies;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TestPlans;
import dev.codingagent.tools.Tool;
import dev.codingagent.tools.ToolDefinition;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.ToolResult;
import dev.codingagent.tools.execution.ToolCallService;

class BudgetTest {
    @TempDir
    Path temporary;
    private ProjectFixture project;
    private final AtomicInteger executions = new AtomicInteger();

    @BeforeEach
    void prepare() throws Exception {
        project = new ProjectFixture(temporary);
    }

    @Test
    void modelRequestIsCommittedBeforeTheClientRunsAndFailureDoesNotRefundIt() throws Exception {
        String id = run(new BudgetLimits(1, 10, 10, 1000));
        ModelClient broken = (history, definitions) -> {
            var saved = new StateStore(project.config).load(id).budget;
            assertEquals(1, saved.modelRequests);
            assertTrue(saved.modelRequestPending);
            assertTrue(saved.modelRequestStartedAt != null);
            throw new IOException("Controlled API failure");
        };
        assertThrows(IOException.class, () -> agent(project.store, broken).run(id));
        StateStore reopened = new StateStore(project.config);
        ModelClient never = (history, definitions) -> { throw new AssertionError("Exceeded request budget"); };
        assertThrows(BudgetExceededException.class, () -> agent(reopened, never).run(id));
        assertEquals(1, reopened.load(id).budget.modelRequests);
    }

    @Test
    void zeroRequestLimitDoesNotCallTheModel() {
        String id = run(new BudgetLimits(0, 10, 10, 1000));
        ModelClient never = (history, definitions) -> { throw new AssertionError("Zero request budget"); };
        assertThrows(BudgetExceededException.class, () -> agent(project.store, never).run(id));
        assertEquals(0, project.store.load(id).budget.modelRequests);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tool_calls", "side_effects", "result_bytes"})
    void insufficientBudgetRejectsTheWholeBatchAndResumeDoesNotPartiallyExecuteIt(String resource) throws Exception {
        BudgetLimits limits = switch (resource) {
            case "tool_calls" -> new BudgetLimits(10, 1, 10, 1000);
            case "side_effects" -> new BudgetLimits(10, 10, 1, 1000);
            default -> new BudgetLimits(10, 10, 10, 3);
        };
        String id = run(limits);
        var reply = ModelReplies.calls(new ToolRequest("first", "counter", "{}"), new ToolRequest("second", "counter", "{}"));
        var model = new ScriptedModelClient(List.of(reply));
        var error = assertThrows(BudgetExceededException.class, () -> agent(project.store, model).run(id));
        assertTrue(error.getMessage().contains(resource));
        assertEquals(0, executions.get());
        var before = project.store.load(id);
        assertEquals(1, before.budget.modelRequests);
        assertEquals(0, before.budget.toolCalls);
        assertEquals(0, before.budget.sideEffects);
        assertTrue(before.budget.results.isEmpty());
        assertTrue(before.calls.values().stream().allMatch(call -> call.attempts().isEmpty()));
        StateStore reopened = new StateStore(project.config);
        assertThrows(BudgetExceededException.class, () -> agent(reopened, model).run(id));
        assertEquals(0, executions.get());
        assertEquals(1, reopened.load(id).budget.modelRequests);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tool_calls", "side_effects"})
    void exhaustedCountersStopTheNextActionAfterAnEarlierSuccess(String resource) throws Exception {
        var limits = resource.equals("tool_calls") ? new BudgetLimits(10, 1, 10, 1000)
                : new BudgetLimits(10, 10, 1, 1000);
        String id = run(limits);
        var script = new ScriptedModelClient(List.of(ModelReplies.calls(new ToolRequest("one", "counter", "{}")),
                ModelReplies.calls(new ToolRequest("two", "counter", "{}"))));
        var error = assertThrows(BudgetExceededException.class, () -> agent(project.store, script).run(id));
        assertTrue(error.getMessage().contains(resource));
        assertEquals(1, executions.get());
        assertEquals(1, project.store.load(id).budget.toolCalls);
        assertEquals(1, project.store.load(id).budget.sideEffects);
        assertThrows(BudgetExceededException.class, () -> agent(new StateStore(project.config), script).run(id));
        assertEquals(1, executions.get());
    }

    @Test
    void cachedRepliesCostToolCallsAndOutputButNeverRepeatOrRechargeTheSideEffect() throws Exception {
        String id = run(new BudgetLimits(3, 2, 1, 1000));
        var call = ModelReplies.calls(new ToolRequest("same", "counter", "{}"));
        var model = new ScriptedModelClient(List.of(call, call, ModelReplies.text("Done")));
        agent(project.store, model).run(id);
        var state = project.store.load(id);
        assertEquals(1, executions.get());
        assertEquals(3, state.budget.modelRequests);
        assertEquals(2, state.budget.toolCalls);
        assertEquals(1, state.budget.sideEffects);
        long sentBytes = state.messages.stream().filter(message -> message.role().equals("tool"))
                .mapToLong(message -> message.json().path("content").asText()
                        .getBytes(StandardCharsets.UTF_8).length).sum();
        assertEquals(sentBytes, state.budget.resultBytes);
        assertEquals(0, state.budget.reservedBytes());
        assertFalse(state.budget.modelRequestPending);
    }

    @Test
    void approvalAndNewStoreDoNotResetPlanningUsage() throws Exception {
        String id = project.createRun("Plan first", RunPhase.PLANNING, new BudgetLimits(3, 10, 10, 1000)).runId;
        var script = new ScriptedModelClient(List.of(ModelReplies.text(TestPlans.JSON),
                ModelReplies.calls(new ToolRequest("execute", "counter", "{}")), ModelReplies.text("Approved execution done")));
        agent(project.store, script).run(id);
        var before = project.store.load(id).budget;
        new PlanService(new StateStore(project.config)).approve(id);
        StateStore reopened = new StateStore(project.config);
        assertEquals(before.modelRequests, reopened.load(id).budget.modelRequests);
        assertEquals(before.limits, reopened.load(id).budget.limits);
        agent(reopened, script).run(id);
        assertEquals(3, reopened.load(id).budget.modelRequests);
        assertEquals(RunStatus.COMPLETED, reopened.load(id).status);
        assertEquals(1, executions.get());
    }

    @Test
    void planningCanConsumeTheLastModelRequestAndExecutionThenStops() throws Exception {
        String id = project.createRun("Plan first", RunPhase.PLANNING, new BudgetLimits(1, 10, 10, 1000)).runId;
        agent(project.store, new ScriptedModelClient(List.of(ModelReplies.text(TestPlans.JSON)))).run(id);
        new PlanService(project.store).approve(id);
        ModelClient never = (history, definitions) -> { throw new AssertionError("Budget reset after approval"); };
        assertThrows(BudgetExceededException.class, () -> agent(new StateStore(project.config), never).run(id));
        assertEquals(1, project.store.load(id).budget.modelRequests);
    }

    @Test
    void pendingBatchReservationSurvivesRestartWithoutBeingChargedTwice() throws Exception {
        String id = project.activeRun();
        var request = new ToolRequest("pending", "counter", "{}");
        var registry = counter();
        project.runs.recordAssistantTurn(id, ModelReplies.calls(request), List.of(registry.describe(request, project.store.load(id).currentTaskId)));
        new BudgetService(project.store).reserveBatch(id, List.of("pending"));
        StateStore reopened = new StateStore(project.config);
        assertTrue(reopened.load(id).calls.get("pending").attempts().isEmpty());
        assertEquals(0, new ToolCallService(reopened).markInterruptedAttempts(id));
        new Agent(reopened, (history, definitions) -> ModelReplies.text("Done"), registry, 5).run(id);
        assertEquals(1, executions.get());
        assertEquals(1, reopened.load(id).budget.toolCalls);
        assertEquals(1, reopened.load(id).budget.sideEffects);
    }

    @Test
    void failedSqlCommitDoesNotSpendARequestOrOpenItsState() throws Exception {
        String id = project.activeRun();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + project.store.databaseFile());
                var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_update AFTER UPDATE ON runs BEGIN SELECT RAISE(ABORT, 'fail'); END");
        }
        assertThrows(IllegalStateException.class, () -> new BudgetService(project.store).beginModelRequest(id));
        var budget = project.store.load(id).budget;
        assertEquals(0, budget.modelRequests);
        assertFalse(budget.modelRequestPending);
    }

    @Test
    void concurrentBatchReservationsShareOneStoredLimit() throws Exception {
        String id = run(new BudgetLimits(10, 1, 1, 1000));
        project.runs.changeStatus(id, RunStatus.EXECUTING);
        var registry = counter();
        project.register(id, registry.describe(new ToolRequest("first", "counter", "{}")),
                registry.describe(new ToolRequest("second", "counter", "{}")));
        Callable<Boolean> first = () -> reserve(id, "first");
        Callable<Boolean> second = () -> reserve(id, "second");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(first, second));
            int accepted = 0;
            for (var result : results) {
                accepted += result.get() ? 1 : 0;
            }
            assertEquals(1, accepted);
        }
        assertEquals(1, project.store.load(id).budget.toolCalls);
        assertEquals(1, project.store.load(id).budget.results.size());
    }

    @Test
    void missingCountersCannotBeSavedAsAnExecutableRun() {
        String id = project.activeRun();
        assertThrows(IllegalArgumentException.class,
                () -> project.store.update(id, state -> { state.budget = null; return null; }));
        assertEquals(0, new StateStore(project.config).load(id).budget.modelRequests);
    }

    private boolean reserve(String id, String callId) {
        try {
            new BudgetService(new StateStore(project.config)).reserveBatch(id, List.of(callId));
            return true;
        } catch (BudgetExceededException exhausted) {
            return false;
        }
    }

    private String run(BudgetLimits limits) {
        return project.createRun("Test budget", RunPhase.EXECUTION, limits).runId;
    }

    private Agent agent(StateStore store, ModelClient model) {
        return new Agent(store, model, counter(), 10);
    }

    private ToolRegistry counter() {
        Tool tool = new Tool() {
            public ToolDefinition definition() {
                return new ToolDefinition("counter", "Count side effects", List.of(), true, false);
            }

            public ToolResult execute(ObjectNode arguments, int outputBytes) {
                executions.incrementAndGet();
                return new ToolResult(true, "{}", false);
            }
        };
        return new ToolRegistry(List.of(tool));
    }
}
