package dev.codingagent.budget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.codingagent.agent.Agent;
import dev.codingagent.json.Json;
import dev.codingagent.model.ScriptedModelClient;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunStatus;
import dev.codingagent.run.recovery.RecoveryAction;
import dev.codingagent.run.recovery.RecoveryService;
import dev.codingagent.storage.StateStore;
import dev.codingagent.support.ModelReplies;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TestProcesses;
import dev.codingagent.tools.ResultLimiter;
import dev.codingagent.tools.Tool;
import dev.codingagent.tools.ToolDefinition;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.ToolResult;
import dev.codingagent.tools.execution.AttemptStatus;
import dev.codingagent.tools.execution.ToolCallService;

class OutputBudgetTest {
    @TempDir
    Path temporary;
    private ProjectFixture project;

    @BeforeEach
    void prepare() throws Exception {
        project = new ProjectFixture(temporary, TestProcesses.bash(), TestProcesses.python());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 10, 20, 40, 80, 160})
    void truncatedResultsStayValidJsonAndWithinTheUtf8Limit(int extraBytes) {
        int limit = ResultLimiter.MIN_BYTES + extraBytes;
        var payload = JsonNodeFactory.instance.objectNode().put("content", "Привет 😀\n\"\\\t".repeat(100));
        var result = ResultLimiter.limit(new ToolResult(true, payload, false), limit);
        assertTrue(result.truncated());
        assertTrue(result.success());
        assertTrue(ResultLimiter.bytes(result) <= limit, result.output().toString());
        assertTrue(result.output().isObject());
        assertFalse(result.output().toString().contains("\ufffd"));
    }

    @Test
    void toolReceivesRemainingAllowanceAndRuntimeBoundsItsEntireResult() throws Exception {
        AtomicInteger observed = new AtomicInteger();
        Tool oversized = new Tool() {
            public ToolDefinition definition() {
                return new ToolDefinition("big", "Return large output", List.of(), false, true);
            }

            public ToolResult execute(ObjectNode arguments, int outputBytes) {
                observed.set(outputBytes);
                var payload = JsonNodeFactory.instance.objectNode().put("content", "😀\n\"\\".repeat(1000));
                return new ToolResult(true, payload, false);
            }
        };
        var tools = new ToolRegistry(List.of(oversized));
        var script = new ScriptedModelClient(List.of(ModelReplies.calls(new ToolRequest("big", "big", "{}")),
                ModelReplies.text("Partial output received")));
        String id = create(new BudgetLimits(2, 1, 0, 50));
        assertThrows(BudgetExceededException.class, () -> new Agent(project.store, script, tools, 5).run(id));
        var state = project.store.load(id);
        var result = state.calls.get("big").latestAttempt().result();
        assertEquals(50, observed.get());
        assertTrue(result.truncated());
        assertTrue(ResultLimiter.bytes(result) <= 50);
        assertEquals(ResultLimiter.bytes(result), state.budget.resultBytes);
        assertEquals(0, state.budget.reservedBytes());
        var toolMessage = state.messages.stream().filter(message -> message.role().equals("tool")).findFirst().orElseThrow();
        assertTrue(Json.parseObject(toolMessage.json().path("content").asText())
                .path("truncated").asBoolean());
    }

    @Test
    void exhaustedOutputDoesNotStartAnotherToolAfterResume() throws Exception {
        AtomicInteger executed = new AtomicInteger();
        Tool tool = new Tool() {
            public ToolDefinition definition() {
                return new ToolDefinition("small", "Return two bytes", List.of(), false, true);
            }

            public ToolResult execute(ObjectNode arguments, int outputBytes) {
                executed.incrementAndGet();
                return new ToolResult(true, "{}", false);
            }
        };
        var tools = new ToolRegistry(List.of(tool));
        var script = new ScriptedModelClient(List.of(ModelReplies.calls(new ToolRequest("one", "small", "{}")),
                ModelReplies.calls(new ToolRequest("two", "small", "{}"))));
        String id = create(new BudgetLimits(10, 10, 0, ResultLimiter.MIN_BYTES));
        assertThrows(BudgetExceededException.class, () -> new Agent(project.store, script, tools, 5).run(id));
        assertEquals(1, executed.get());
        assertEquals(ResultLimiter.MIN_BYTES, project.store.load(id).budget.resultBytes);
        StateStore reopened = new StateStore(project.config);
        assertThrows(BudgetExceededException.class, () -> new Agent(reopened, script, tools, 5).run(id));
        assertEquals(1, executed.get());
        assertTrue(reopened.load(id).calls.get("two").attempts().isEmpty());
    }

    @Test
    void actualBashOutputIsAlsoBoundedByTheRemainingRunBudget() throws Exception {
        var tools = ToolRegistry.defaults(project.config, ToolLimits.defaults());
        var shell = ModelReplies.calls(new ToolRequest("shell", "Bash",
                "{\"command\":\"printf '%0200d' 0\",\"cwd\":\".\"}"));
        var script = new ScriptedModelClient(List.of(shell, ModelReplies.text("Command checked")));
        String id = create(new BudgetLimits(2, 1, 1, 64));
        assertThrows(BudgetExceededException.class, () -> new Agent(project.store, script, tools, 5).run(id));
        var state = project.store.load(id);
        var result = state.calls.get("shell").latestAttempt().result();
        assertTrue(result.success(), result.output().toString());
        assertTrue(result.truncated());
        assertTrue(ResultLimiter.bytes(result) <= 64);
        assertEquals(ResultLimiter.bytes(result), state.budget.resultBytes);
        assertEquals(1, state.budget.sideEffects);
        assertEquals(0, state.budget.reservedBytes());
    }

    @Test
    void crashBurnsTheReservationAndRetryCannotRefundIt() throws Exception {
        Files.writeString(project.config.workspace().resolve("file.txt"), "content");
        var tools = ToolRegistry.defaults(project.config, ToolLimits.defaults());
        var read = ModelReplies.calls(new ToolRequest("read", "read_file", "{\"path\":\"file.txt\"}"));
        var script = new ScriptedModelClient(List.of(read, ModelReplies.text("Done after skip")));
        String id = create(new BudgetLimits(2, 2, 0, 64));
        Agent crashing = new Agent(project.store, script, tools, 5,
                (call, result) -> { throw new IOException("Crash after reading"); });
        assertThrows(IOException.class, () -> crashing.run(id));
        var before = project.store.load(id).budget;
        assertEquals(64, before.reservedBytes());
        StateStore reopened = new StateStore(project.config);
        new ToolCallService(reopened).markInterruptedAttempts(id);
        var burned = reopened.load(id).budget;
        assertEquals(64, burned.resultBytes);
        assertEquals(0, burned.reservedBytes());
        assertEquals(before.resultBytes + before.reservedBytes(), burned.resultBytes + burned.reservedBytes());
        var recovery = new RecoveryService(reopened, tools);
        assertThrows(BudgetExceededException.class, () -> recovery.resolve(id, "read", RecoveryAction.RETRY));
        assertEquals(1, reopened.load(id).calls.get("read").attempts().size());
        assertTrue(reopened.load(id).decisions.isEmpty());
        assertEquals(1, reopened.load(id).budget.toolCalls);
        recovery.resolve(id, "read", RecoveryAction.SKIP);
        assertThrows(BudgetExceededException.class, () -> new Agent(reopened, script, tools, 5).run(id));
        assertEquals(64, reopened.load(id).budget.resultBytes);
        assertEquals(RunStatus.EXECUTING, reopened.load(id).status);
        assertTrue(reopened.load(id).budget.results.isEmpty());
    }

    @Test
    void safeRetryStillRequiresAnotherToolCallFromTheSavedBudget() throws Exception {
        Files.writeString(project.config.workspace().resolve("file.txt"), "content");
        var tools = ToolRegistry.defaults(project.config, ToolLimits.defaults());
        var read = ModelReplies.calls(new ToolRequest("read", "read_file", "{\"path\":\"file.txt\"}"));
        var script = new ScriptedModelClient(List.of(read));
        String id = create(new BudgetLimits(10, 1, 0, 50_000));
        assertThrows(IOException.class, () -> new Agent(project.store, script, tools, 5,
                (call, result) -> { throw new IOException("Crash"); }).run(id));
        project.calls.markInterruptedAttempts(id);
        var error = assertThrows(BudgetExceededException.class,
                () -> new RecoveryService(project.store, tools).resolve(id, "read", RecoveryAction.RETRY));
        assertTrue(error.getMessage().contains("tool_calls"));
        var state = project.store.load(id);
        assertEquals(1, state.budget.toolCalls);
        assertEquals(1, state.calls.get("read").attempts().size());
        assertEquals(AttemptStatus.UNCERTAIN, state.calls.get("read").latestAttempt().status());
        assertTrue(state.decisions.isEmpty());
    }

    @Test
    void explicitRetrySpendsAnotherToolCallAndKeepsTheBurnedBytes() throws Exception {
        Files.writeString(project.config.workspace().resolve("file.txt"), "content");
        var tools = ToolRegistry.defaults(project.config, ToolLimits.defaults());
        var read = ModelReplies.calls(new ToolRequest("read", "read_file", "{\"path\":\"file.txt\"}"));
        var script = new ScriptedModelClient(List.of(read, ModelReplies.text("Done after retry")));
        String id = create(new BudgetLimits(2, 2, 0, 50_000));
        assertThrows(IOException.class, () -> new Agent(project.store, script, tools, 5,
                (call, result) -> { throw new IOException("Crash"); }).run(id));
        project.calls.markInterruptedAttempts(id);
        long burned = project.store.load(id).budget.resultBytes;
        new RecoveryService(project.store, tools).resolve(id, "read", RecoveryAction.RETRY);
        var ready = project.store.load(id);
        assertEquals(2, ready.budget.toolCalls);
        assertEquals(AttemptStatus.READY, ready.calls.get("read").latestAttempt().status());
        assertEquals(burned, ready.budget.resultBytes);
        new Agent(new StateStore(project.config), script, tools, 5).run(id);
        var finished = project.store.load(id);
        assertEquals(2, finished.budget.toolCalls);
        assertEquals(burned + ResultLimiter.bytes(finished.calls.get("read").latestAttempt().result()),
                finished.budget.resultBytes);
        assertEquals(0, finished.budget.reservedBytes());
    }

    private String create(BudgetLimits limits) {
        return project.createRun("Test output budget", RunPhase.EXECUTION, limits).runId;
    }
}
