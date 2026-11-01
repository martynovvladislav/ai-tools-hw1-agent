package dev.codingagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import dev.codingagent.json.Json;
import dev.codingagent.tools.ToolResult;

class JsonPayloadTest {
    @Test
    void messageOwnsItsFullJsonAndSurvivesStorageWithoutAStringWrapper() {
        var original = Json.parseObject("""
                {"role":"assistant","content":"Done","reasoning_content":"kept",
                 "provider_extra":{"tokens":17,"fraction":1.5}}
                """);
        var message = new ChatMessage(original);
        original.put("content", "modified outside");
        ((ObjectNode) message.json().path("provider_extra")).put("tokens", 999);

        assertEquals("Done", message.json().path("content").asText());
        assertEquals(17, message.json().path("provider_extra").path("tokens").asInt());
        assertEquals("kept", message.json().path("reasoning_content").asText());
        assertEquals(message, Json.read(Json.write(message), ChatMessage.class));
        assertEquals("assistant", Json.parseObject(Json.write(message)).path("role").asText());
        assertFalse(Json.parseObject(Json.write(message)).has("payloadJson"));
    }

    @Test
    void resultOwnsItsOutputAndToolContentStillUsesTheApiStringFormat() {
        var output = JsonNodeFactory.instance.objectNode().put("exit_code", 0).put("stdout", "Привет 😀");
        var result = new ToolResult(true, output, false);
        output.put("exit_code", 9);
        result.output().put("exit_code", 8);
        assertEquals(0, result.output().path("exit_code").asInt());
        assertEquals(result, Json.read(Json.write(result), ToolResult.class));
        assertTrue(result.output().path("exit_code").isIntegralNumber());

        var reply = ChatMessage.tool("call-1", result);
        assertTrue(reply.json().path("content").isTextual());
        var content = Json.parseObject(reply.json().path("content").asText());
        assertTrue(content.path("success").asBoolean());
        assertEquals(0, content.path("output").path("exit_code").asInt());
        assertFalse(content.path("truncated").asBoolean());
    }
}
