package dev.codingagent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.codingagent.json.Json;
import dev.codingagent.model.ToolRequest;
import dev.codingagent.plan.PlanStep;
import dev.codingagent.run.RunStatus;
import dev.codingagent.support.ModelReplies;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TaskFixtures;
import dev.codingagent.tools.ToolLimits;
import dev.codingagent.tools.ToolRegistry;

class TaskGraphTest {
    @TempDir
    Path temporary;
    private ProjectFixture project;
    private ToolRegistry tools;

    @BeforeEach
    void prepare() throws Exception {
        project = new ProjectFixture(temporary);
        tools = ToolRegistry.defaults(project.config, ToolLimits.defaults());
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "unknown", "self", "cycle", "mixed", "missing_result"})
    void invalidGraphsAreRejectedBeforeExecution(String kind) {
        assertThrows(IllegalArgumentException.class, () -> {
            var read = new TaskCheck("read_file", "a.txt", null);
            var first = TaskFixtures.step("first", List.of(), read);
            switch (kind) {
                case "duplicate" -> TaskFixtures.plan(first, TaskFixtures.step("first", List.of(), read));
                case "unknown" -> TaskFixtures.plan(first, TaskFixtures.step("second", List.of("missing"), read));
                case "self" -> TaskFixtures.plan(TaskFixtures.step("first", List.of("first"), read));
                case "cycle" -> TaskFixtures.plan(TaskFixtures.step("first", List.of("second"), read),
                        TaskFixtures.step("second", List.of("first"), read));
                case "mixed" -> TaskFixtures.plan(first, new PlanStep(null, "Missing ID", List.of("read_file"), List.of(), null));
                case "missing_result" -> new PlanStep("first", "No evidence condition", List.of("read_file"), List.of(),
                        new ExpectedResult("Only a claim", List.of()));
                default -> throw new AssertionError(kind);
            }
        });
    }

    @Test
    void runtimeSelectsReadyDependenciesEvenWhenThePlanIsNotTopologicallyOrdered() throws Exception {
        Files.writeString(project.config.workspace().resolve("a.txt"), "content");
        var verify = TaskFixtures.step("verify", List.of("fix"), new TaskCheck("read_file", "a.txt", null));
        var inspect = TaskFixtures.step("inspect", List.of(), new TaskCheck("read_file", "a.txt", null));
        var fix = TaskFixtures.step("fix", List.of("inspect"), new TaskCheck("write_file", "a.txt", null));
        String id = TaskFixtures.approvedRun(project, verify, inspect, fix);
        var tasks = new TaskService(project.store);
        tasks.startNext(id);
        assertEquals("inspect", project.store.load(id).currentTaskId);
        var request = new ToolRequest("inspect-file", "read_file", "{\"path\":\"a.txt\"}");
        var definition = tools.describe(request, "inspect");
        project.runs.recordAssistantTurn(id, ModelReplies.calls(request), List.of(definition));
        var attempt = project.calls.startAttempt(id, request.id());
        project.calls.finishAttempt(id, request.id(), attempt.attempt().number(), tools.execute(definition));
        project.runs.recordAssistantTurn(id, ModelReplies.text("Inspection complete"), List.of());
        assertTrue(tasks.completeCurrent(id));
        tasks.startNext(id);
        assertEquals("fix", project.store.load(id).currentTaskId);
        assertEquals(TaskStatus.PENDING, project.store.load(id).tasks.getFirst().status);
        assertThrows(IllegalStateException.class, () -> project.runs.changeStatus(id, RunStatus.COMPLETED));
    }

    @Test
    void aCallCannotBeRegisteredForAnotherTaskOrPartiallySaveItsAssistantTurn() {
        var first = TaskFixtures.step("first", List.of(), new TaskCheck("read_file", "a.txt", null));
        var second = TaskFixtures.step("second", List.of("first"), new TaskCheck("write_file", "a.txt", null));
        String id = TaskFixtures.approvedRun(project, first, second);
        new TaskService(project.store).startNext(id);
        String before = Json.write(project.store.load(id));
        var request = new ToolRequest("wrong-owner", "write_file", "{\"path\":\"a.txt\",\"content\":\"bad\"}");
        assertThrows(IllegalArgumentException.class, () -> project.runs.recordAssistantTurn(id, ModelReplies.calls(request),
                List.of(tools.describe(request, "second"))));
        assertEquals(before, Json.write(project.store.load(id)));
        assertFalse(Files.exists(project.config.workspace().resolve("a.txt")));
    }

    @Test
    void evenAForgedCurrentTaskCannotStartBeforeItsDependencySucceeds() {
        var first = TaskFixtures.step("first", List.of(), new TaskCheck("read_file", "a.txt", null));
        var second = TaskFixtures.step("second", List.of("first"), new TaskCheck("write_file", "a.txt", null));
        String id = TaskFixtures.approvedRun(project, first, second);
        project.store.update(id, state -> {
            state.currentTaskId = "second";
            state.tasks.get(1).status = TaskStatus.RUNNING;
            return null;
        });
        assertThrows(IllegalStateException.class, () -> project.register(id,
                tools.describe(new ToolRequest("blocked", "write_file", "{}"), "second")));
        assertTrue(project.store.load(id).calls.isEmpty());
        assertEquals(0, project.store.load(id).budget.toolCalls);
    }
}
