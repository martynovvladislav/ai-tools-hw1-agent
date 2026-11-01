package dev.codingagent.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;

import dev.codingagent.journal.JournalService;
import dev.codingagent.journal.rollback.RollbackService;
import dev.codingagent.storage.StateStore;

final class JournalCommand {
    private static final Set<String> OPTIONS = Set.of("--run-id", "--workspace", "--state-dir");
    private final PrintStream out;

    JournalCommand(PrintStream out) {
        this.out = out;
    }

    int execute(String[] args) throws IOException {
        if (args.length == 2 && (args[1].equals("--help") || args[1].equals("-h"))) {
            out.println("Usage: java -jar target/coding-agent.jar " + args[0]
                    + " --run-id ID [--workspace PATH] [--state-dir PATH]");
            out.println("Stop the executor first. rollback restores owned paths only, after an all-path conflict check.");
            out.println("Resolve uncertain calls and explicitly attribute observed changes with resume before rollback.");
            return 0;
        }
        var options = CliOptions.parse(args, OPTIONS);
        String id = CliOptions.required(options, "--run-id");
        var store = new StateStore(CliOptions.config(options));
        if (args[0].equals("rollback")) {
            new RollbackService(store).rollback(id);
            RunView.print(store.load(id), out);
        } else {
            var journal = new JournalService(store);
            journal.observePending(id);
            JournalView.diff(store.load(id), journal.currentTree(), out);
        }
        return 0;
    }
}
