package dev.codingagent.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;
import java.util.Set;

import dev.codingagent.journal.ChangeAttribution;
import dev.codingagent.journal.JournalService;
import dev.codingagent.run.RunStatus;
import dev.codingagent.run.recovery.RecoveryAction;
import dev.codingagent.run.recovery.RecoveryService;
import dev.codingagent.tools.execution.ToolCallService;

final class ResumeCommand {
    private static final Set<String> OPTIONS = Set.of("--run-id", "--workspace", "--state-dir", "--bash",
            "--python", "--script", "--max-turns", "--crash-after-call", "--call-id", "--action",
            "--changes", "--observation");
    private final PrintStream out;

    ResumeCommand(PrintStream out) {
        this.out = out;
    }

    int execute(String[] args) throws IOException, InterruptedException {
        if (args.length == 2 && (args[1].equals("--help") || args[1].equals("-h"))) {
            out.println("""
                    Usage: java -jar target/coding-agent.jar resume --run-id ID [OPTIONS]
                    Use the same --workspace and --state-dir as the original run.
                    Model options: --script PATH or API environment; --bash, --python, --max-turns.
                    An uncertain call requires --call-id ID --action retry|skip|fail.
                    Retry is allowed only for trusted retry-safe tools. Bash/write_file are not retry-safe.
                    If several calls are uncertain, resolve each before execution continues.
                    Stop the original executor before resume. Concurrent executors are not supported.
                    Demo fault injection: --crash-after-call ID.
                    Saved budget limits and counters are reused; resume cannot change them.
                    For uncertain side effects, first inspect with resume without --action.
                    Then supply --changes include|exclude --observation HASH with the recovery action.
                    """);
            return 0;
        }
        var options = CliOptions.parse(args, OPTIONS);
        String id = CliOptions.required(options, "--run-id");
        if (options.containsKey("--action") != options.containsKey("--call-id")) {
            throw new IllegalArgumentException("--action and --call-id must be supplied together");
        }
        RecoveryAction action = options.containsKey("--action")
                ? RecoveryAction.valueOf(options.get("--action").toUpperCase(Locale.ROOT)) : null;
        if (options.containsKey("--changes") != options.containsKey("--observation")
                || (action == null && options.containsKey("--changes"))) {
            throw new IllegalArgumentException("--changes and --observation require each other and a recovery action");
        }
        ChangeAttribution attribution = options.containsKey("--changes")
                ? ChangeAttribution.valueOf(options.get("--changes").toUpperCase(Locale.ROOT)) : null;
        var session = CliSession.open(options);
        var state = session.store().load(id);
        if (state.status.isTerminal()) {
            if (action != null) {
                throw new IllegalStateException("Run is already terminal");
            }
            RunView.print(state, out);
            return state.status == RunStatus.FAILED ? 1 : 0;
        }
        new ToolCallService(session.store()).markInterruptedAttempts(id);
        if (action != null) {
            new RecoveryService(session.store(), session.tools()).resolve(id, options.get("--call-id"), action,
                    attribution, options.get("--observation"));
        } else {
            new JournalService(session.store()).observePending(id);
        }
        state = session.store().load(id);
        if (state.status == RunStatus.RECOVERY_REQUIRED || state.status == RunStatus.AWAITING_APPROVAL
                || state.status == RunStatus.FAILED) {
            RunView.print(state, out);
            return state.status == RunStatus.FAILED ? 1 : 0;
        }
        String answer = session.run(id, out);
        state = session.store().load(id);
        if (state.status == RunStatus.AWAITING_APPROVAL) {
            RunView.print(state, out);
        } else {
            out.println(answer);
        }
        return 0;
    }
}
