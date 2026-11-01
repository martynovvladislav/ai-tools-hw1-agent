package dev.codingagent.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.codingagent.support.ModelReplies;
import dev.codingagent.tools.ToolResult;

class ScriptedModelClientTest {
    @Test
    void exhaustionReportsTheLastToolFailure() {
        var model = new ScriptedModelClient(List.of(ModelReplies.text("Done")));
        var history = List.of(ModelReplies.text("Done"), ChatMessage.tool("run-tests",
                new ToolResult(false, "{\"stderr\":\"Python command not found\",\"exit_code\":127}", false)));

        var error = assertThrows(IllegalStateException.class, () -> model.generate(history, List.of()));
        assertTrue(error.getMessage().contains("run-tests"));
        assertTrue(error.getMessage().contains("Python command not found"));
        assertTrue(error.getMessage().contains("127"));
    }

    @Test
    void exhaustionDoesNotBlameAnEarlierFailureAfterASuccessfulToolResult() {
        var model = new ScriptedModelClient(List.of(ModelReplies.text("Done")));
        var history = List.of(ModelReplies.text("Done"),
                ChatMessage.tool("old-failure", ToolResult.error("Old error")),
                ChatMessage.tool("verified", new ToolResult(true, "{}", false)));

        var error = assertThrows(IllegalStateException.class, () -> model.generate(history, List.of()));
        assertTrue(error.getMessage().contains("no reply for turn 1"));
        assertFalse(error.getMessage().contains("Old error"));
    }
}
