package dev.codingagent.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;

import dev.codingagent.run.RunPhase;
import dev.codingagent.run.RunService;
import dev.codingagent.run.RunStatus;

final class RunCommand {
    private static final Set<String> OPTIONS = Set.of("--task", "--workspace", "--state-dir",
            "--bash", "--python", "--script", "--max-turns", "--crash-after-call",
            "--max-model-requests", "--max-tool-calls", "--max-side-effects", "--max-result-bytes");
    private final PrintStream out;

    RunCommand(PrintStream out) {
        this.out = out;
    }

    int execute(String[] args) throws IOException, InterruptedException {
        if (args.length == 2 && (args[1].equals("--help") || args[1].equals("-h"))) {
            printHelp();
            return 0;
        }
        var options = CliOptions.parse(args, OPTIONS);
        String task = options.get("--task");
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException(args[0] + " requires --task TEXT");
        }
        var limits = BudgetOptions.parse(options);
        var session = CliSession.open(options);
        RunPhase phase = args[0].equals("plan") ? RunPhase.PLANNING : RunPhase.EXECUTION;
        var runs = new RunService(session.store());
        var run = runs.create(task, phase, limits);
        out.println("run_id: " + run.runId);
        String answer = session.run(run.runId, out);
        var state = session.store().load(run.runId);
        if (state.status == RunStatus.AWAITING_APPROVAL) {
            RunView.print(state, out);
        } else {
            out.println(answer);
        }
        return 0;
    }

    private void printHelp() {
        out.println("""
                Usage: java -jar target/coding-agent.jar run|plan --task TEXT [OPTIONS]

                  --workspace PATH    Project to modify (default: ./workspace).
                  --state-dir PATH    SQLite state directory (default: ./.agent-state).
                  --bash COMMAND      Bash executable (default: installed Git Bash, otherwise bash).
                  --python COMMAND    Python exposed to Bash as AGENT_PYTHON.
                  --script PATH       Deterministic replies instead of a real model.
                  --max-turns NUMBER  Model responses per invocation (default: 20).
                  --crash-after-call ID  Demo: fail after tool execution, before saving its result.
                  --max-model-requests N  Requests for the entire run (default: 40).
                  --max-tool-calls N      Tool requests/retries for the entire run (default: 100).
                  --max-side-effects N    Admitted side-effect actions (default: 30).
                  --max-result-bytes N    UTF-8 result JSON bytes (default: 262144).

                API mode reads OPENAI_BASE_URL, OPENAI_MODEL and optional OPENAI_API_KEY.
                Scripts exercise the same runtime but do not perform model reasoning.
                plan permits only read tools and waits for approve/reject. After approval use resume.
                Budget limits are saved once and cannot be reset by resume or approval.
                run uses one task with tool evidence; plan can define a dependency graph with expected_result checks.
                """);
    }
}
