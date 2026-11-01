package dev.codingagent.run;

import static dev.codingagent.support.ProjectFixture.readCall;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import dev.codingagent.json.Json;
import dev.codingagent.model.ChatMessage;
import dev.codingagent.plan.PlanService;
import dev.codingagent.storage.StateStore;
import dev.codingagent.support.ModelReplies;
import dev.codingagent.support.ProjectFixture;
import dev.codingagent.support.TestPlans;
import dev.codingagent.task.TaskService;
import dev.codingagent.tools.ToolResult;

class RunServiceTest {
    @TempDir
    Path temporary;
    private ProjectFixture project;

    @BeforeEach
    void prepareProject() throws IOException {
        project = new ProjectFixture(temporary);
    }

    @ParameterizedTest
    @EnumSource(RunPhase.class)
    void firstSavedSnapshotAlreadyContainsItsPhaseInstructionsAndRequiredState(RunPhase phase) {
        var created = project.createRun("Inspect the project", phase);
        var saved = new StateStore(project.config).load(created.runId);
        assertEquals(phase == RunPhase.PLANNING ? RunStatus.PLANNING : RunStatus.EXECUTING, saved.status);
        assertEquals(phase, saved.phase);
        assertEquals("system", saved.messages.getFirst().role());
        assertEquals("Inspect the project", saved.messages.get(1).json().path("content").asText());
        assertEquals(phase == RunPhase.PLANNING ? 3 : 2, saved.messages.size());
        assertEquals(phase == RunPhase.PLANNING ? 0 : 1, saved.tasks.size());
        assertNotNull(saved.budget);
        assertNotNull(saved.journal.expectedTree);
        assertEquals(0, saved.budget.modelRequests);
        assertEquals(List.of(), saved.decisions);
    }

    @Test
    void restoresRunFullMessagesAndDecisionsFromAnotherStoreInstance() {
        RunState run = project.createRun("Исправь расчёт заказа", RunPhase.PLANNING);
        var plans = new PlanService(project.store);
        plans.saveDraft(run.runId, TestPlans.JSON);
        plans.approve(run.runId);
        ChatMessage assistant = new ChatMessage(Json.parseObject("""
                {"role":"assistant","content":null,"tool_calls":[
                  {"id":"call-1","type":"function","function":{"name":"read_file","arguments":"{}"}}
                ],"custom_field":{"keep":true}}
                """));
        project.runs.appendMessage(run.runId, assistant);
        var decision = project.store.load(run.runId).decisions.getFirst();

        RunState reopened = new StateStore(project.config).load(run.runId);
        assertEquals(run.goal, reopened.goal);
        assertEquals(run.createdAt, reopened.createdAt);
        assertEquals(5, reopened.messages.size());
        assertEquals(assistant, reopened.messages.getLast());
        assertEquals(List.of(decision), reopened.decisions);
        assertNotEquals(run.runId, project.createRun("Another run").runId);
    }

    @Test
    void enforcesRunLifecycleAndKeepsPlanningAndExecutionPhases() {
        String id = project.createRun("Plan first", RunPhase.PLANNING).runId;
        assertThrows(IllegalStateException.class, () -> project.runs.changeStatus(id, RunStatus.COMPLETED));
        assertEquals(RunPhase.PLANNING, project.store.load(id).phase);
        var plans = new PlanService(project.store);
        plans.saveDraft(id, TestPlans.JSON);
        assertThrows(IllegalStateException.class, () -> project.runs.changeStatus(id, RunStatus.EXECUTING));
        plans.approve(id);
        assertEquals(RunPhase.EXECUTION, project.store.load(id).phase);
        project.runs.changeStatus(id, RunStatus.FAILED);

        assertThrows(IllegalStateException.class, () -> project.runs.changeStatus(id, RunStatus.EXECUTING));
        assertThrows(IllegalStateException.class, () -> project.runs.appendMessage(id, ChatMessage.user("More")));
    }

    @Test
    void cannotClaimCompletionWithUnfinishedCalls() {
        String id = project.activeRun();
        project.register(id, readCall("pending"));
        assertThrows(IllegalStateException.class, () -> project.runs.changeStatus(id, RunStatus.COMPLETED));

        project.calls.startAttempt(id, "pending");
        assertThrows(IllegalStateException.class, () -> project.runs.changeStatus(id, RunStatus.COMPLETED));

        project.calls.finishAttempt(id, "pending", 1, new ToolResult(true, "{}", false));
        assertThrows(IllegalStateException.class, () -> project.runs.changeStatus(id, RunStatus.COMPLETED));
        project.runs.recordAssistantTurn(id, ModelReplies.text("Verified"), List.of());
        new TaskService(project.store).completeCurrent(id);
        assertEquals(RunStatus.COMPLETED, project.store.load(id).status);
    }
}
