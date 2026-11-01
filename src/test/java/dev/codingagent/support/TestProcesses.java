package dev.codingagent.support;

import java.nio.file.Files;
import java.nio.file.Path;

public final class TestProcesses {
    private TestProcesses() {
    }

    public static String bash() {
        String override = System.getProperty("test.bash");
        if (override != null) {
            return override;
        }
        Path gitBash = Path.of("C:/Program Files/Git/bin/bash.exe");
        return Files.isRegularFile(gitBash) ? gitBash.toString() : "bash";
    }

    public static String python() {
        String override = System.getProperty("test.python");
        if (override != null) {
            return override;
        }
        Path msysPython = Path.of("C:/msys64/ucrt64/bin/python.exe");
        return Files.isRegularFile(msysPython) ? msysPython.toString() : "python3";
    }
}
