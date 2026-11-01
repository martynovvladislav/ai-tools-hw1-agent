package dev.codingagent.cli;

import java.io.PrintStream;
import java.util.Set;

import dev.codingagent.plan.PlanService;
import dev.codingagent.storage.StateStore;

/** Viewing state and recording a plan decision need neither a model nor a Bash process. */
final class PlanCommand {
    private static final Set<String> OPTIONS = Set.of("--run-id", "--workspace", "--state-dir");
    private final PrintStream out;

    PlanCommand(PrintStream out) {
        this.out = out;
    }

    int execute(String[] args) {
        if (args.length == 2 && (args[1].equals("--help") || args[1].equals("-h"))) {
            out.println("Usage: java -jar target/coding-agent.jar " + args[0]
                    + " --run-id ID [--workspace PATH] [--state-dir PATH]");
            out.println("Use the same workspace/state-dir. approve records permission; resume starts execution.");
            return 0;
        }
        var options = CliOptions.parse(args, OPTIONS);
        String id = CliOptions.required(options, "--run-id");
        var store = new StateStore(CliOptions.config(options));
        var plans = new PlanService(store);
        switch (args[0]) {
            case "approve" -> plans.approve(id);
            case "reject" -> plans.reject(id);
            case "show", "tasks" -> { }
            default -> throw new IllegalArgumentException("Unknown plan command: " + args[0]);
        }
        if (args[0].equals("tasks")) {
            TaskView.print(store.load(id), out, true);
        } else {
            RunView.print(store.load(id), out);
        }
        if (args[0].equals("approve")) {
            out.println("Approval saved. Continue with resume --run-id " + id + " and the same workspace/state-dir.");
        }
        return 0;
    }
}
