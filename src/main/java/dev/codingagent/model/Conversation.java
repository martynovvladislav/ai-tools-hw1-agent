package dev.codingagent.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Finds unfinished work in the saved history before requesting another model response. */
public final class Conversation {
    private Conversation() {
    }

    public static List<ToolRequest> pendingCalls(List<ChatMessage> history) {
        int assistant = lastAssistant(history);
        if (assistant < 0) {
            return List.of();
        }
        AssistantTurn turn = AssistantTurn.parse(history.get(assistant));
        Set<String> answered = new HashSet<>();
        for (int index = assistant + 1; index < history.size(); index++) {
            ChatMessage message = history.get(index);
            if (message.role().equals("tool")) {
                answered.add(message.json().path("tool_call_id").asText());
            }
        }
        List<ToolRequest> pending = new ArrayList<>();
        for (ToolRequest call : turn.calls()) {
            if (!answered.contains(call.id())) {
                pending.add(call);
            }
        }
        return pending;
    }

    public static Optional<String> finalText(List<ChatMessage> history) {
        if (history.isEmpty() || !history.getLast().role().equals("assistant")) {
            return Optional.empty();
        }
        AssistantTurn turn = AssistantTurn.parse(history.getLast());
        return turn.calls().isEmpty() ? Optional.of(turn.text()) : Optional.empty();
    }

    private static int lastAssistant(List<ChatMessage> history) {
        for (int index = history.size() - 1; index >= 0; index--) {
            if (history.get(index).role().equals("assistant")) {
                return index;
            }
        }
        return -1;
    }
}
