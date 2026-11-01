package dev.codingagent.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.codingagent.budget.BudgetLimits;
import dev.codingagent.config.AgentConfig;
import dev.codingagent.plan.PlanStep;
import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunService;
import dev.codingagent.run.RunState;
import dev.codingagent.storage.StateStore;
import dev.codingagent.task.ExpectedResult;
import dev.codingagent.task.TaskCheck;
import dev.codingagent.task.TaskService;
import dev.codingagent.tools.execution.ToolCall;
import dev.codingagent.tools.execution.ToolCallService;

/** A separate workspace and database for each test. */
public final class ProjectFixture {
    public final AgentConfig config;
    public final StateStore store;
    public final RunService runs;
    public final ToolCallService calls;

    public ProjectFixture(Path directory) throws IOException {
        this(directory, "bash", "python3");
    }

    public ProjectFixture(Path directory, String bash, String python) throws IOException {
        Path workspace = Files.createDirectory(directory.resolve("workspace"));
        config = new AgentConfig(workspace, directory.resolve("state"), bash, python);
        store = new StateStore(config);
        runs = new RunService(store);
        calls = new ToolCallService(store);
    }

    public String activeRun() {
        RunState state = createRun("Fix order");
        new TaskService(store).startNext(state.runId);
        return state.runId;
    }

    public RunState createRun(String goal) {
        return createRun(goal, RunPhase.EXECUTION, BudgetLimits.defaults());
    }

    public RunState createRun(String goal, RunPhase phase) {
        return createRun(goal, phase, BudgetLimits.defaults());
    }

    /** Protocol/service tests use generic evidence; production coding runs require Bash verification. */
    public RunState createRun(String goal, RunPhase phase, BudgetLimits limits) {
        RunState run = runs.create(goal, phase, limits);
        if (phase == RunPhase.EXECUTION) {
            store.update(run.runId, state -> {
                var task = state.tasks.getFirst();
                var definition = task.definition;
                task.definition = new PlanStep(definition.id(), definition.description(), definition.tools(),
                        definition.dependsOn(), new ExpectedResult("Successful tool evidence for this unit test",
                                List.of(new TaskCheck("any", null, null))));
                return null;
            });
        }
        return store.load(run.runId);
    }

    public void register(String runId, ToolCall... definitions) {
        var state = store.load(runId);
        if (state.phase == RunPhase.EXECUTION && state.currentTaskId == null) {
            new TaskService(store).startNext(runId);
            state = store.load(runId);
        }
        String taskId = state.currentTaskId;
        var owned = List.of(definitions).stream().map(call -> new ToolCall(call.toolCallId(), call.name(),
                call.argumentsJson(), call.sideEffect(), call.retrySafe(), call.taskId() == null ? taskId : call.taskId())).toList();
        store.update(runId, saved -> { ToolCallService.registerInState(saved, owned); return null; });
    }

    public static ToolCall readCall(String id) {
        return new ToolCall(id, "read_file", "{\"path\":\"order.py\"}", false, true);
    }
}
