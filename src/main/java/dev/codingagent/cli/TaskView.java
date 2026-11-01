package dev.codingagent.cli;

import java.io.PrintStream;

import dev.codingagent.json.Json;
import dev.codingagent.run.RunState;
import dev.codingagent.task.TaskStatus;

final class TaskView {
    private TaskView() {
    }

    static void print(RunState state, PrintStream out) {
        print(state, out, false);
    }

    static void print(RunState state, PrintStream out, boolean details) {
        out.println("Tasks:");
        for (var task : state.tasks) {
            String mark = task.status == TaskStatus.COMPLETED ? "[x]" : "[ ]";
            out.println(mark + " " + task.definition.id() + " (" + task.status + "): " + task.definition.description());
            if (!task.definition.dependsOn().isEmpty()) {
                out.println("    depends_on: " + String.join(", ", task.definition.dependsOn()));
            }
            out.println("    expected: " + task.definition.expectedResult().description());
            for (var proof : task.evidence) {
                out.println("    proof: " + proof.toolCallId() + "#" + proof.attempt() + ", " + proof.check().tool()
                        + ", success=" + proof.result().success());
                if (details) {
                    out.println("    check: " + Json.write(proof.check()));
                    out.println("    output: " + proof.result().output());
                }
            }
        }
    }
}
