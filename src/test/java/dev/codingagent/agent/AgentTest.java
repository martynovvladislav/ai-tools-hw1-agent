package dev.codingagent.agent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.codingagent.model.ModelClient;
import dev.codingagent.model.ScriptedModelClient;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.run.RunStatus;
import dev.codingagent.support.JournalFixtures;
import dev.codingagent.support.ModelReplies;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TestProcesses;
import dev.codingagent.tools.Tool;
import dev.codingagent.tools.ToolDefinition;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.ToolResult;

class AgentTest {
    @TempDir
    Path temporary;
    private ProjectFixture project;

    @BeforeEach
    void prepare() throws Exception {
        project = new ProjectFixture(temporary, TestProcesses.bash(), TestProcesses.python());
    }

    @Test
    void repairsTheControlProjectAndReallyRunsUnittestThroughBash() throws Exception {
        byte[] originalTests = Files.readAllBytes(Path.of("workspace/test_order.py"));
        for (String file : List.of("order.py", "test_order.py")) {
            Files.copy(Path.of("workspace", file), project.config.workspace().resolve(file));
        }
        String id = project.runs.create("Fix the order calculation. Do not change tests.").runId;
        ModelClient model = ScriptedModelClient.fromFile(Path.of("examples/order-repair-responses.json"));
        String answer = agent(model, defaults(), 10).run(id);

        var state = project.store.load(id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertFalse(answer.isBlank());
        assertTrue(state.calls.get("fix-code").definition().sideEffect());
        var verification = state.calls.get("run-tests").latestAttempt().result();
        assertTrue(verification.success(), verification.output().toString());
        var output = verification.output();
        assertEquals(0, output.path("exit_code").asInt());
        assertTrue((output.path("stdout").asText() + output.path("stderr").asText()).contains("Ran 2 tests"));
        assertArrayEquals(originalTests, Files.readAllBytes(project.config.workspace().resolve("test_order.py")));
        assertArrayEquals(originalTests, Files.readAllBytes(Path.of("workspace/test_order.py")));
        assertFalse(Files.exists(project.config.workspace().resolve("__pycache__")));
    }

    @Test
    void aCodingRunCannotCompleteAfterOnlyReadingAndSuggestingCode() throws Exception {
        String id = project.runs.create("Fix the project and verify the result").runId;
        var model = new ScriptedModelClient(List.of(
                ModelReplies.calls(new ToolRequest("inspect", "list_files", "{\"path\":\".\"}")),
                ModelReplies.text("Here is the proposed code; apply it yourself.")));
        assertThrows(IllegalStateException.class, () -> agent(model, defaults(), 2).run(id));
        var state = project.store.load(id);
        assertEquals(RunStatus.EXECUTING, state.status);
        assertTrue(state.tasks.getFirst().evidence.isEmpty());
        assertEquals("Bash", state.tasks.getFirst().definition.expectedResult().checks().getFirst().tool());
        assertTrue(state.calls.get("inspect").latestAttempt().result().success());
        assertTrue(state.messages.getLast().json().path("content").asText().contains("Cannot complete task"));
    }

    @Test
    void repeatedFailuresRequestInvestigationAndCanThenBeFixedAndVerified() throws Exception {
        var model = new ScriptedModelClient(List.of(
                ModelReplies.calls(new ToolRequest("failed-first", "Bash", "{\"command\":\"test -f ready.txt\",\"cwd\":\".\"}")),
                ModelReplies.calls(new ToolRequest("failed-again", "Bash", "{\"cwd\":\".\",\"command\":\"test -f ready.txt\"}")),
                ModelReplies.calls(new ToolRequest("fix", "write_file", "{\"path\":\"ready.txt\",\"content\":\"ready\"}")),
                ModelReplies.calls(new ToolRequest("verify", "Bash", "{\"command\":\"test -f ready.txt\",\"cwd\":\".\"}")),
                ModelReplies.text("Verified")));
        AtomicInteger requests = new AtomicInteger();
        ModelClient checked = (history, definitions) -> {
            if (requests.incrementAndGet() == 3) {
                assertEquals("user", history.getLast().role());
                assertTrue(history.getLast().json().path("content").asText().startsWith("Repeated tool failure:"));
            }
            return model.generate(history, definitions);
        };
        String id = project.runs.create("Create the requested file and verify it").runId;
        assertEquals("Verified", agent(checked, defaults(), 8).run(id));
        var state = project.store.load(id);
        assertEquals(RunStatus.COMPLETED, state.status);
        assertFalse(state.calls.get("failed-again").latestAttempt().result().success());
        assertTrue(state.calls.get("verify").latestAttempt().result().success());
        assertEquals(1, state.messages.stream().filter(message -> message.json().path("content").asText()
                .startsWith("Repeated tool failure:")).count());
    }

    @Test
    void toolErrorsAreReturnedWithTheirIdsAndDoNotStopTheLoop() throws Exception {
        var requests = ModelReplies.calls(new ToolRequest("unknown", "missing-tool", "{}"),
                new ToolRequest("malformed", "read_file", "{"),
                new ToolRequest("wrong-type", "read_file", "{\"path\":7}"),
                new ToolRequest("verified", "list_files", "{\"path\":\".\"}"));
        ModelClient model = new ScriptedModelClient(List.of(requests, ModelReplies.text("Errors handled")));
        String id = project.createRun("Inspect the project").runId;
        assertEquals("Errors handled", agent(model, defaults(), 5).run(id));
        var state = project.store.load(id);
        for (String callId : List.of("unknown", "malformed", "wrong-type")) {
            assertFalse(state.calls.get(callId).latestAttempt().result().success());
            assertTrue(state.messages.stream().anyMatch(message -> message.role().equals("tool")
                    && message.json().path("tool_call_id").asText().equals(callId)));
        }
    }

    @Test
    void repeatedIdReusesTheResultAndRepliesToTheNewAssistantTurn() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        var call = ModelReplies.calls(new ToolRequest("same", "counter", "{}"));
        ModelClient model = new ScriptedModelClient(List.of(call, call, ModelReplies.text("Done")));
        String id = project.createRun("Count once").runId;
        agent(model, counter(executions), 5).run(id);
        assertEquals(1, executions.get());
        assertEquals(1, project.store.load(id).calls.get("same").attempts().size());
        assertEquals(2, project.store.load(id).messages.stream().filter(message -> message.role().equals("tool")).count());
    }

    @Test
    void savedPendingWorkIsExecutedBeforeAnotherModelRequest() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = counter(executions);
        String id = project.activeRun();
        ToolRequest request = new ToolRequest("pending", "counter", "{}");
        project.runs.recordAssistantTurn(id, ModelReplies.calls(request), List.of(registry.describe(request, project.store.load(id).currentTaskId)));
        ModelClient model = (history, tools) -> {
            assertEquals(1, executions.get());
            return ModelReplies.text("Finished pending work");
        };
        assertEquals("Finished pending work", agent(model, registry, 3).run(id));
    }

    @Test
    void savedFinalAnswerCompletesWithoutCallingTheModelAgain() throws Exception {
        String id = project.activeRun();
        JournalFixtures.execute(project, defaults(), id,
                new ToolRequest("evidence", "list_files", "{\"path\":\".\"}"));
        project.runs.recordAssistantTurn(id, ModelReplies.text("Already done"), List.of());
        ModelClient mustNotRun = (history, tools) -> { throw new AssertionError("Model called after final answer"); };
        Agent agent = agent(mustNotRun, defaults(), 3);
        assertEquals("Already done", agent.run(id));
        assertEquals(RunStatus.COMPLETED, project.store.load(id).status);
        assertEquals("Already done", agent.run(id));
    }

    @Test
    void startedAttemptIsNotSilentlyRepeated() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ToolRegistry registry = counter(executions);
        String id = project.activeRun();
        ToolRequest request = new ToolRequest("uncertain", "counter", "{}");
        project.runs.recordAssistantTurn(id, ModelReplies.calls(request), List.of(registry.describe(request, project.store.load(id).currentTaskId)));
        project.calls.startAttempt(id, request.id());
        ModelClient mustNotRun = (history, tools) -> { throw new AssertionError("Model called before recovery"); };
        assertThrows(IllegalStateException.class, () -> agent(mustNotRun, registry, 3).run(id));
        assertEquals(0, executions.get());
    }

    @Test
    void changedDefinitionDoesNotSaveAPartialAssistantTurnOrExecuteAgain() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        var first = ModelReplies.calls(new ToolRequest("same", "counter", "{}"));
        var conflicting = ModelReplies.calls(new ToolRequest("same", "counter", "{\"changed\":true}"));
        String id = project.createRun("Count once").runId;
        var model = new ScriptedModelClient(List.of(first, conflicting));
        assertThrows(IllegalArgumentException.class, () -> agent(model, counter(executions), 5).run(id));
        assertEquals(1, executions.get());
        assertEquals(1, project.store.load(id).messages.stream().filter(message -> message.role().equals("assistant")).count());
    }

    @Test
    void invocationTurnLimitStopsAnEndlessModel() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        ModelClient model = (history, tools) -> ModelReplies.calls(
                new ToolRequest("call-" + requests.incrementAndGet(), "counter", "{}"));
        String id = project.createRun("Do work").runId;
        assertThrows(IllegalStateException.class, () -> agent(model, counter(executions), 2).run(id));
        assertEquals(2, requests.get());
        assertEquals(2, executions.get());
    }

    private Agent agent(ModelClient model, ToolRegistry tools, int maxTurns) {
        return new Agent(project.store, model, tools, maxTurns);
    }

    private ToolRegistry defaults() throws Exception {
        return ToolRegistry.defaults(project.config, ToolLimits.defaults());
    }

    private static ToolRegistry counter(AtomicInteger executions) {
        Tool tool = new Tool() {
            public ToolDefinition definition() {
                return new ToolDefinition("counter", "Count executions", List.of(), false, true);
            }

            public ToolResult execute(ObjectNode arguments, int outputBytes) {
                return new ToolResult(true, "{\"count\":" + executions.incrementAndGet() + "}", false);
            }
        };
        return new ToolRegistry(List.of(tool));
    }
}
