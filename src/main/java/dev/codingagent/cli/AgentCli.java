package dev.codingagent.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;

/** Dispatches console commands; CliSession builds their runtime dependencies. */
public final class AgentCli {
    private static final Set<String> DOCTOR_OPTIONS = Set.of(
            "--workspace", "--state-dir", "--bash", "--python");

    private final PrintStream out;
    private final PrintStream err;

    public AgentCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public int run(String[] args) {
        try {
            if (args.length == 0 || (args.length == 1 && isHelp(args[0]))) {
                printHelp();
                return 0;
            }
            if (args.length == 1 && "--version".equals(args[0])) {
                String version = AgentCli.class.getPackage().getImplementationVersion();
                out.println("coding-agent " + (version == null ? "dev" : version));
                return 0;
            }
            if ("doctor".equals(args[0])) {
                if (args.length == 2 && isHelp(args[1])) {
                    printDoctorHelp();
                    return 0;
                }
                return new EnvironmentDoctor(out).check(CliOptions.config(CliOptions.parse(args, DOCTOR_OPTIONS)));
            }
            if ("run".equals(args[0]) || "plan".equals(args[0])) {
                return new RunCommand(out).execute(args);
            }
            if ("resume".equals(args[0])) {
                return new ResumeCommand(out).execute(args);
            }
            if (Set.of("diff", "rollback").contains(args[0])) {
                return new JournalCommand(out).execute(args);
            }
            if (Set.of("approve", "reject", "show", "tasks").contains(args[0])) {
                return new PlanCommand(out).execute(args);
            }
            throw new IllegalArgumentException("Unknown command: " + args[0]);
        } catch (IllegalArgumentException exception) {
            err.println("Error: " + exception.getMessage());
            err.println("Use --help for available commands.");
            return 2;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            err.println("Run interrupted; state is preserved.");
            return 1;
        } catch (IOException | IllegalStateException exception) {
            err.println("Run failed: " + exception.getMessage());
            return 1;
        }
    }

    private static boolean isHelp(String argument) {
        return "--help".equals(argument) || "-h".equals(argument);
    }

    private void printHelp() {
        out.println("""
                Usage: java -jar target/coding-agent.jar [--help | --version | COMMAND OPTIONS]

                Console coding agent with persistent execution state.

                Commands:
                  run          Execute a coding task using a model or scripted replies.
                  plan         Inspect using read tools and save a plan awaiting approval.
                  approve      Record approval of a saved plan; then use resume.
                  reject       Reject a saved plan and fail the run.
                  resume       Continue a stopped run, resolving uncertain calls explicitly.
                  show         Show saved status, budget, plan and uncertain calls.
                  tasks        Show the task checklist and saved verification evidence.
                  diff         Show recorded file changes and unattributed observations.
                  rollback     Explicitly restore owned paths after a conflict preflight.
                  doctor       Check Java, workspace, Bash and Python without modifying project files.
                  --help       Show this help.
                  --version    Show the application version.

                Use COMMAND --help for options. Budgets persist across planning, execution and resume.
                """);
    }

    private void printDoctorHelp() {
        out.println("""
                Usage: java -jar target/coding-agent.jar doctor [OPTIONS]

                  --workspace PATH    Target project directory (default: ./workspace).
                  --state-dir PATH    State directory (default: ./.agent-state).
                  --bash COMMAND      Bash executable (default: installed Git Bash, otherwise bash).
                  --python COMMAND    Python for the control project (default: python3).

                Executable values are single names or paths, without shell arguments.
                Bash and Python are checked with --version and a 5-second timeout each.
                The state directory is not created.
                """);
    }
}
