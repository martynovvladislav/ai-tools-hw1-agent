package dev.codingagent.task;

import java.util.ArrayList;
import java.util.List;

import dev.codingagent.json.Json;
import dev.codingagent.run.RunState;
import dev.codingagent.tools.execution.ToolCallState;

/** Checks saved attempts using the original reply position, so cache replay cannot refresh evidence. */
final class TaskEvidenceCollector {
    private TaskEvidenceCollector() {
    }

    static List<TaskEvidence> collect(RunState state, TaskState task) {
        List<ToolCallState> calls = state.calls.values().stream()
                .filter(call -> task.definition.id().equals(call.definition().taskId())).toList();
        if (calls.stream().anyMatch(call -> call.latestAttempt() == null || !call.latestAttempt().isFinished())) {
            throw new IllegalStateException("Task still has unfinished calls");
        }
        int lastEffect = calls.stream().filter(call -> call.definition().sideEffect() || call.definition().name().equals("Bash"))
                .mapToInt(call -> firstReplyIndex(state, call.definition().toolCallId())).max().orElse(-1);
        List<TaskEvidence> evidence = new ArrayList<>();
        for (TaskCheck check : task.definition.expectedResult().checks()) {
            ToolCallState matched = calls.stream().filter(call -> matches(state, call, check, lastEffect)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Missing fresh, successful, untruncated evidence: " + Json.write(check)));
            var attempt = matched.latestAttempt();
            evidence.add(new TaskEvidence(check, matched.definition().toolCallId(), attempt.number(),
                    attempt.finishedAt(), attempt.result()));
        }
        return evidence;
    }

    private static boolean matches(RunState state, ToolCallState call, TaskCheck check, int lastEffect) {
        var attempt = call.latestAttempt();
        if (!attempt.result().success() || attempt.result().truncated()
                || firstReplyIndex(state, call.definition().toolCallId()) < lastEffect) {
            return false;
        }
        if (check.tool().equals("any")) {
            return true;
        }
        if (!call.definition().name().equals(check.tool())) {
            return false;
        }
        var args = Json.parseObject(call.definition().argumentsJson());
        var output = attempt.result().output();
        String pathArgument = check.tool().equals("Bash") ? "cwd" : "path";
        if (check.path() != null && !check.path().equals(args.path(pathArgument).asText())) {
            return false;
        }
        String text;
        switch (check.tool()) {
            case "read_file" -> {
                if (!output.path("content").isTextual()) {
                    return false;
                }
                text = output.path("content").asText();
            }
            case "write_file" -> {
                return output.path("path").asText().equals(args.path("path").asText())
                        && output.path("bytes_written").isIntegralNumber() && output.path("bytes_written").asLong() >= 0;
            }
            case "Bash" -> {
                if (!output.path("exit_code").isIntegralNumber() || output.path("exit_code").asInt() != 0
                        || !output.path("timed_out").isBoolean() || output.path("timed_out").asBoolean()
                        || !output.path("stdout").isTextual() || !output.path("stderr").isTextual()) {
                    return false;
                }
                text = output.path("stdout").asText() + output.path("stderr").asText();
            }
            case "list_files" -> {
                if (!output.path("files").isArray()) {
                    return false;
                }
                text = output.path("files").toString();
            }
            default -> { return false; }
        }
        return check.contains() == null || text.contains(check.contains());
    }

    private static int firstReplyIndex(RunState state, String callId) {
        for (int index = 0; index < state.messages.size(); index++) {
            var message = state.messages.get(index);
            if (message.role().equals("tool")
                    && message.json().path("tool_call_id").asText().equals(callId)) {
                return index;
            }
        }
        throw new IllegalStateException("Finished call has no original tool reply: " + callId);
    }
}
