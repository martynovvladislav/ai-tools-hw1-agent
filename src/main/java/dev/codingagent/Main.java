package dev.codingagent;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import dev.codingagent.cli.AgentCli;

/** Entry point for the executable JAR. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        if (System.console() == null) {
            // Keep redirected output readable in UTF-8 shells, including PowerShell.
            System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        }
        System.exit(new AgentCli(System.out, System.err).run(args));
    }
}
