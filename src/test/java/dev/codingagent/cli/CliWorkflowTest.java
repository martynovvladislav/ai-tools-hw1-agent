package dev.codingagent.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.codingagent.budget.BudgetLimits;
import dev.codingagent.json.Json;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.run.RunStatus;
import dev.codingagent.support.JournalFixtures;
import dev.codingagent.support.ModelReplies;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TaskFixtures;
import dev.codingagent.support.TestPlans;
import dev.codingagent.support.TestProcesses;
import dev.codingagent.task.TaskCheck;
import dev.codingagent.task.TaskStatus;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;
import dev.codingagent.tools.execution.AttemptStatus;

class CliWorkflowTest {
    @TempDir
    Path temporary;
    private ProjectFixture project;
    private Path script;

    @BeforeEach
    void prepare() throws Exception {
        project = new ProjectFixture(temporary, TestProcesses.bash(), TestProcesses.python());
        script = temporary.resolve("replies.json");
    }

    @Test
    void scriptedRepairRunsTestsWithTheDefaultBash() throws Exception {
        prepareControlProject();
        byte[] originalTests = Files.readAllBytes(project.config.workspace().resolve("test_order.py"));
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        int code = new AgentCli(new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8)).run(new String[] {
                    "run", "--task", "Fix order calculation without changing tests",
                    "--workspace", project.config.workspace().toString(),
                    "--state-dir", project.config.stateDirectory().toString(),
                    "--python", TestProcesses.python(),
                    "--script", "examples/order-repair-responses.json"
                });

        var result = new CliResult(code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
        assertEquals(0, result.code(), result.error());
        var state = project.store.load(result.runId());
        assertEquals(RunStatus.COMPLETED, state.status);
        var verification = state.calls.get("run-tests").latestAttempt().result();
        assertTrue(verification.success(), verification.output().toString());
        assertTrue(verification.output().path("stderr").asText().contains("Ran 2 tests"));
        assertArrayEquals(originalTests,
                Files.readAllBytes(project.config.workspace().resolve("test_order.py")));
    }

    @Test
    void planWaitsAcrossCommandsAndExecutesOnlyAfterApproveAndResume() throws Exception {
        writeScript(taskPlanReply(), writeReply(), ModelReplies.text("Executed approved plan"));
        var planned = invoke("plan", "--task", "Plan first", "--script", script.toString());
        assertEquals(0, planned.code(), planned.error());
        String id = planned.runId();
        assertEquals(RunStatus.AWAITING_APPROVAL, project.store.load(id).status);
        assertFalse(Files.exists(outputFile()));
        assertTrue(invoke("show", "--run-id", id).output().toString().contains("done when:"));

        // No script/API is needed to inspect or wait for a decision.
        var waiting = invoke("resume", "--run-id", id, "--script", "does-not-exist.json");
        assertEquals(0, waiting.code(), waiting.error());
        assertFalse(Files.exists(outputFile()));
        assertEquals(0, invoke("approve", "--run-id", id).code());
        assertEquals(1, project.store.load(id).budget.modelRequests);
        assertFalse(Files.exists(outputFile()));
        var resumed = invoke("resume", "--run-id", id, "--script", script.toString());
        assertEquals(0, resumed.code(), resumed.error());
        assertEquals("agent content", Files.readString(outputFile()));
        assertEquals(RunStatus.COMPLETED, project.store.load(id).status);
        assertEquals(3, project.store.load(id).budget.modelRequests);
        assertEquals(0, invoke("resume", "--run-id", id, "--script", "does-not-exist.json").code());
    }

    @Test
    void realWriteCrashIsShownAndRequiresExplicitSkipBeforeContinuation() throws Exception {
        var verify = Json.parseObject("{\"cwd\":\".\"}").put("command",
                "test \"$(cat output.txt)\" = 'external content after crash'");
        writeScript(writeReply(), ModelReplies.calls(new ToolRequest("verify-after-skip", "read_file",
                "{\"path\":\"output.txt\"}")), ModelReplies.calls(new ToolRequest("verify-bash", "Bash", Json.write(verify))),
                ModelReplies.text("Continued after verifying the file"));
        var crashed = invoke("run", "--task", "Write", "--script", script.toString(), "--crash-after-call", "write");
        assertEquals(1, crashed.code());
        assertTrue(crashed.error().contains("Simulated crash"));
        String id = crashed.runId();
        assertEquals("agent content", Files.readString(outputFile()));
        Files.writeString(outputFile(), "external content after crash");

        var waiting = invoke("resume", "--run-id", id, "--script", "does-not-exist.json");
        assertEquals(0, waiting.code(), waiting.error());
        assertTrue(waiting.output().toString().contains("uncertain call: write"));
        assertEquals(1, invoke("resume", "--run-id", id, "--call-id", "write", "--action", "retry").code());
        assertTrue(project.store.load(id).decisions.isEmpty());
        var skipped = invoke("resume", "--run-id", id, "--call-id", "write", "--action", "skip",
                "--changes", "exclude", "--observation", project.store.load(id).journal.actions.getLast().observation,
                "--script", script.toString());
        assertEquals(0, skipped.code(), skipped.error());
        assertEquals("external content after crash", Files.readString(outputFile()));
        assertEquals(RunStatus.COMPLETED, project.store.load(id).status);
        assertTrue(invoke("diff", "--run-id", id).output().toString().contains("Excluded observation: write#1"));
    }

    @Test
    void retrySafeReadCreatesAnotherAttemptButExternalChangesBlockTheNextBash() throws Exception {
        Files.writeString(outputFile(), "old content");
        writeScript(ModelReplies.calls(new ToolRequest("read", "read_file", "{\"path\":\"output.txt\"}")),
                ModelReplies.calls(new ToolRequest("verify-bash", "Bash", "{\"command\":\"cat output.txt\",\"cwd\":\".\"}")),
                ModelReplies.text("Read complete"));
        var crashed = invoke("run", "--task", "Read", "--script", script.toString(), "--crash-after-call", "read");
        assertEquals(1, crashed.code());
        String id = crashed.runId();
        Files.writeString(outputFile(), "new content");
        var retried = invoke("resume", "--run-id", id, "--call-id", "read", "--action", "retry",
                "--script", script.toString());
        assertEquals(1, retried.code(), retried.error());
        assertTrue(retried.error().contains("Journal conflict"));
        var call = project.store.load(id).calls.get("read");
        assertEquals(2, call.attempts().size());
        assertEquals(AttemptStatus.UNCERTAIN, call.attempts().getFirst().status());
        assertTrue(call.latestAttempt().result().output().toString().contains("new content"));
        assertTrue(project.store.load(id).calls.get("verify-bash").attempts().isEmpty());
        assertEquals("new content", Files.readString(outputFile()));
    }

    @Test
    void savedFinalAnswerCanCompleteWithoutOpeningAScriptOrApiClient() throws Exception {
        String id = project.activeRun();
        JournalFixtures.execute(project,
                ToolRegistry.defaults(project.config, ToolLimits.defaults()), id,
                new ToolRequest("evidence", "list_files", "{\"path\":\".\"}"));
        project.runs.recordAssistantTurn(id, ModelReplies.text("Saved before the crash"), List.of());
        var resumed = invoke("resume", "--run-id", id, "--script", "does-not-exist.json");
        assertEquals(0, resumed.code(), resumed.error());
        assertTrue(resumed.output().toString().contains("Saved before the crash"));
        assertEquals(RunStatus.COMPLETED, project.store.load(id).status);
    }

    @Test
    void cliPersistsLimitsShowsUsageAndResumeCannotOverrideThem() throws Exception {
        var requests = ModelReplies.calls(new ToolRequest("first", "write_file",
                "{\"path\":\"output.txt\",\"content\":\"first\"}"), new ToolRequest("second", "write_file",
                "{\"path\":\"other.txt\",\"content\":\"second\"}"));
        writeScript(requests, ModelReplies.text("Must not execute"));
        var refused = invoke("run", "--task", "Write", "--script", script.toString(),
                "--max-model-requests", "4", "--max-tool-calls", "1", "--max-side-effects", "2", "--max-result-bytes", "100");
        assertEquals(1, refused.code());
        assertTrue(refused.error().contains("tool_calls"));
        String id = refused.runId();
        assertFalse(Files.exists(outputFile()));
        assertEquals(new BudgetLimits(4, 1, 2, 100), project.store.load(id).budget.limits);
        var shown = invoke("show", "--run-id", id);
        assertTrue(shown.output().toString().contains("budget model_requests: 1/4"));
        assertTrue(shown.output().toString().contains("budget tool_calls: 0/1"));
        assertEquals(2, invoke("resume", "--run-id", id, "--max-tool-calls", "100").code());
        assertEquals(1, invoke("resume", "--run-id", id, "--script", script.toString()).code());
        assertEquals(1, project.store.load(id).budget.modelRequests);
        assertFalse(Files.exists(outputFile()));
    }

    @Test
    void taskChecklistAndEvidenceSurviveCliResumeWithoutRepeatingCompletedWork() throws Exception {
        for (String file : List.of("order.py", "test_order.py")) {
            Files.copy(Path.of("workspace", file), project.config.workspace().resolve(file));
        }
        script = Path.of("examples/order-plan-responses.json").toAbsolutePath();
        var planned = invoke("plan", "--task", "Fix the order", "--script", script.toString());
        assertEquals(0, planned.code(), planned.error());
        String id = planned.runId();
        assertTrue(planned.output().toString().contains("[ ] inspect (PENDING)"));
        assertEquals(0, invoke("approve", "--run-id", id).code());
        var stopped = invoke("resume", "--run-id", id, "--script", script.toString(), "--max-turns", "3");
        assertEquals(1, stopped.code());
        assertTrue(stopped.error().contains("Model turn limit reached"));
        assertTrue(stopped.output().toString().contains("[x] inspect (COMPLETED)"));
        assertEquals(TaskStatus.COMPLETED, project.store.load(id).tasks.getFirst().status);
        assertEquals(TaskStatus.RUNNING, project.store.load(id).tasks.get(1).status);
        assertEquals(TaskStatus.PENDING, project.store.load(id).tasks.get(2).status);
        String completedInspection = Json.write(project.store.load(id).tasks.getFirst());
        var partial = invoke("tasks", "--run-id", id);
        assertEquals(0, partial.code());
        assertTrue(partial.output().toString().contains("proof: read-code#1"));
        var resumed = invoke("resume", "--run-id", id, "--script", script.toString());
        assertEquals(0, resumed.code(), resumed.error());
        assertTrue(resumed.output().toString().contains("[x] verify (COMPLETED)"));
        assertEquals(completedInspection, Json.write(project.store.load(id).tasks.getFirst()));
        assertEquals(1, project.store.load(id).calls.get("read-code").attempts().size());
        var details = invoke("tasks", "--run-id", id);
        assertTrue(details.output().toString().contains("proof: run-tests#1"));
        assertTrue(details.output().toString().contains("Ran 2 tests"));
        assertEquals(9, project.store.load(id).budget.modelRequests);
        assertEquals(8, project.store.load(id).budget.toolCalls);
    }

    @Test
    void newCliPlansRequireTaskChecksAndCannotSilentlyUseTheOldFlatFormat() throws Exception {
        var oldPlan = Json.parseObject(TestPlans.JSON);
        var step = (ObjectNode) oldPlan.path("steps").get(0);
        step.remove("id");
        step.remove("expected_result");
        writeScript(ModelReplies.text(Json.write(oldPlan)));
        var refused = invoke("plan", "--task", "Plan", "--script", script.toString(), "--max-turns", "1");
        assertEquals(1, refused.code());
        var state = project.store.load(refused.runId());
        assertEquals(RunStatus.PLANNING, state.status);
        assertTrue(state.tasks.isEmpty());
        assertTrue(state.messages.getLast().json().toString().contains("Invalid plan:"));
        assertTrue(state.calls.isEmpty());
    }

    @Test
    void controlProjectDiffAndRollbackWorkAfterResumeAndPreserveOriginalTests() throws Exception {
        prepareControlProject();
        byte[] original = Files.readAllBytes(project.config.workspace().resolve("order.py"));
        byte[] originalTests = Files.readAllBytes(project.config.workspace().resolve("test_order.py"));
        script = Path.of("examples/order-repair-responses.json").toAbsolutePath();
        var run = invoke("run", "--task", "Fix the order", "--script", script.toString());
        assertEquals(0, run.code(), run.error());
        String id = run.runId();
        assertEquals(0, invoke("resume", "--run-id", id).code());
        var diff = invoke("diff", "--run-id", id);
        assertEquals(0, diff.code(), diff.error());
        assertTrue(diff.output().toString().contains("diff -- order.py"));
        assertTrue(diff.output().toString().contains("-    return sum(prices) - discount_percent"));
        assertTrue(diff.output().toString().contains("+    return sum(prices) * (1 - discount_percent / 100)"));
        var rollback = invoke("rollback", "--run-id", id);
        assertEquals(0, rollback.code(), rollback.error());
        assertEquals(RunStatus.ROLLED_BACK, project.store.load(id).status);
        assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(project.config.workspace().resolve("order.py"))));
        assertTrue(java.util.Arrays.equals(originalTests, Files.readAllBytes(project.config.workspace().resolve("test_order.py"))));
    }

    @Test
    void cliRequiresReviewAndAttributionBeforeRollingBackAnUncertainWrite() throws Exception {
        writeScript(writeReply());
        var crashed = invoke("run", "--task", "Write", "--script", script.toString(), "--crash-after-call", "write");
        assertEquals(1, crashed.code());
        String id = crashed.runId();
        assertEquals(1, invoke("rollback", "--run-id", id).code());
        var inspection = invoke("resume", "--run-id", id);
        assertTrue(inspection.output().toString().contains("Unattributed changes: write#1"));
        assertTrue(inspection.output().toString().contains("+agent content"));
        assertEquals(1, invoke("resume", "--run-id", id, "--call-id", "write", "--action", "fail").code());
        String token = project.store.load(id).journal.actions.getFirst().observation;
        var failed = invoke("resume", "--run-id", id, "--call-id", "write", "--action", "fail",
                "--changes", "include", "--observation", token);
        assertEquals(1, failed.code());
        assertEquals(RunStatus.FAILED, project.store.load(id).status);
        assertEquals(0, invoke("rollback", "--run-id", id).code());
        assertFalse(Files.exists(outputFile()));
    }

    @Test
    void failChoiceAndPlanRejectionAreTerminal() throws Exception {
        writeScript(writeReply(), ModelReplies.text("Must not reach this answer"));
        var crashed = invoke("run", "--task", "Write", "--script", script.toString(), "--crash-after-call", "write");
        invoke("resume", "--run-id", crashed.runId());
        var failed = invoke("resume", "--run-id", crashed.runId(), "--call-id", "write", "--action", "fail",
                "--changes", "include", "--observation", project.store.load(crashed.runId()).journal.actions.getLast().observation);
        assertEquals(1, failed.code());
        assertEquals(RunStatus.FAILED, project.store.load(crashed.runId()).status);

        writeScript(taskPlanReply(), writeReply());
        var planned = invoke("plan", "--task", "Plan", "--script", script.toString());
        assertEquals(0, invoke("reject", "--run-id", planned.runId()).code());
        assertEquals(RunStatus.FAILED, project.store.load(planned.runId()).status);
        assertEquals(1, invoke("resume", "--run-id", planned.runId()).code());
    }

    private void prepareControlProject() throws Exception {
        Files.writeString(project.config.workspace().resolve("order.py"), """
                def calculate_total(prices: list[float], discount_percent: float) -> float:
                    return sum(prices) - discount_percent
                """);
        Files.copy(Path.of("workspace/test_order.py"), project.config.workspace().resolve("test_order.py"));
    }

    private CliResult invoke(String command, String... arguments) {
        List<String> args = new ArrayList<>(List.of(command, "--workspace", project.config.workspace().toString(),
                "--state-dir", project.config.stateDirectory().toString()));
        if (List.of("run", "plan", "resume").contains(command)) {
            args.addAll(List.of("--bash", TestProcesses.bash(), "--python", TestProcesses.python()));
        }
        args.addAll(List.of(arguments));
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        int code = new AgentCli(new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8)).run(args.toArray(String[]::new));
        return new CliResult(code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private void writeScript(ChatMessage... replies) throws Exception {
        var root = Json.parseObject("{\"responses\":[]}");
        var responses = root.withArray("responses");
        for (var reply : replies) {
            responses.add(reply.json());
        }
        Files.writeString(script, Json.write(root));
    }

    private ChatMessage writeReply() {
        return ModelReplies.calls(new ToolRequest("write", "write_file",
                "{\"path\":\"output.txt\",\"content\":\"agent content\"}"));
    }

    private ChatMessage taskPlanReply() {
        return ModelReplies.text(Json.write(TaskFixtures.plan(TaskFixtures.step("implement", List.of(),
                new TaskCheck("write_file", "output.txt", null)))));
    }

    private Path outputFile() {
        return project.config.workspace().resolve("output.txt");
    }

    private record CliResult(int code, String output, String error) {
        String runId() {
            return output.lines().filter(line -> line.startsWith("run_id: ")).findFirst()
                    .orElseThrow(() -> new AssertionError(output + error)).substring("run_id: ".length());
        }
    }
}
