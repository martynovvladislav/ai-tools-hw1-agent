package dev.codingagent.task;

import java.time.Instant;

import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunState;
import dev.codingagent.tools.execution.ToolCall;

/** Ownership and dependency guards shared by registration, budgets and actual execution. */
public final class TaskRules {
    private TaskRules() {
    }

    static TaskState find(RunState state, String id) {
        return state.tasks.stream().filter(task -> task.definition.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown task: " + id));
    }

    static boolean dependenciesComplete(RunState state, TaskState task) {
        return task.definition.dependsOn().stream().allMatch(id -> find(state, id).status == TaskStatus.COMPLETED);
    }

    static TaskState current(RunState state) {
        if (state.currentTaskId == null) {
            throw new IllegalStateException("No task is running");
        }
        TaskState task = find(state, state.currentTaskId);
        if (task.status != TaskStatus.RUNNING || !dependenciesComplete(state, task)) {
            throw new IllegalStateException("Current task is not executable: " + state.currentTaskId);
        }
        return task;
    }

    public static void requireOwner(RunState state, ToolCall call) {
        if (state.phase == RunPhase.PLANNING) {
            if (call.taskId() != null) {
                throw new IllegalArgumentException("Planning calls must not have task_id");
            }
        } else {
            TaskState task = current(state);
            if (!task.definition.id().equals(call.taskId())) {
                throw new IllegalArgumentException("Tool call must belong to the current task: " + task.definition.id());
            }
        }
    }

    public static void requireAllComplete(RunState state) {
        if (state.tasks.stream().anyMatch(task -> task.status != TaskStatus.COMPLETED)) {
            throw new IllegalStateException("Run still has unfinished tasks");
        }
    }

    public static void failCurrent(RunState state) {
        if (state.currentTaskId != null) {
            TaskState task = find(state, state.currentTaskId);
            if (task.status == TaskStatus.RUNNING) {
                task.status = TaskStatus.FAILED;
                task.finishedAt = Instant.now().toString();
            }
        }
    }
}
