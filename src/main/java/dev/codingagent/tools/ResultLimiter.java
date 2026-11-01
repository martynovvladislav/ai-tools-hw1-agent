package dev.codingagent.tools;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import dev.codingagent.json.Json;

/** Bounds the full tool content JSON, including status flags, escaping and UTF-8 encoding. */
public final class ResultLimiter {
    public static final int MIN_BYTES = bytes(new ToolResult(false, "{}", true));

    private ResultLimiter() {
    }

    public static int bytes(ToolResult result) {
        return result.contentJson().getBytes(StandardCharsets.UTF_8).length;
    }

    public static ToolResult limit(ToolResult result, int limit) {
        if (limit < MIN_BYTES) {
            throw new IllegalArgumentException("Result byte limit must be at least " + MIN_BYTES);
        }
        if (bytes(result) <= limit) {
            return result;
        }
        var best = JsonNodeFactory.instance.objectNode();
        String text = Json.write(result.output());
        int low = 0;
        int high = text.length();
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int end = middle;
            if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
                end--;
            }
            var payload = JsonNodeFactory.instance.objectNode().put("preview", text.substring(0, end));
            if (bytes(new ToolResult(result.success(), payload, true)) <= limit) {
                best = payload;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return new ToolResult(result.success(), best, true);
    }
}
