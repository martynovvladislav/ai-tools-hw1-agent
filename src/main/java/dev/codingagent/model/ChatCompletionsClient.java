package dev.codingagent.model;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.codingagent.json.Json;
import dev.codingagent.tools.ToolDefinition;

/** Minimal non-streaming OpenAI-compatible Chat Completions adapter. */
public final class ChatCompletionsClient implements ModelClient {
    private final ModelConfig config;
    private final HttpClient client;

    public ChatCompletionsClient(ModelConfig config) {
        this.config = config;
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public ChatMessage generate(List<ChatMessage> history, List<ToolDefinition> tools)
            throws IOException, InterruptedException {
        return request(history, tools, null);
    }

    @Override
    public ChatMessage generateStructured(List<ChatMessage> history, ObjectNode schema)
            throws IOException, InterruptedException {
        return request(history, List.of(), schema);
    }

    private ChatMessage request(List<ChatMessage> history, List<ToolDefinition> tools, ObjectNode schema)
            throws IOException, InterruptedException {
        var body = JsonNodeFactory.instance.objectNode().put("model", config.model());
        var messages = body.putArray("messages");
        for (ChatMessage message : history) {
            var copy = (ObjectNode) message.json();
            if (message.role().equals("assistant")) {
                copy.remove(List.of("reasoning", "reasoning_content", "thinking"));
            }
            messages.add(copy);
        }
        if (!tools.isEmpty()) {
            var definitions = body.putArray("tools");
            for (ToolDefinition tool : tools) {
                definitions.add(tool.apiSchema());
            }
            body.put("tool_choice", "auto");
        }
        if (schema != null) {
            var format = body.putObject("response_format").put("type", "json_schema");
            format.putObject("json_schema").put("name", "work_plan").set("schema", schema);
        }

        var request = HttpRequest.newBuilder(config.endpoint()).timeout(config.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
        if (!config.apiKey().isBlank()) {
            request.header("Authorization", "Bearer " + config.apiKey());
        }
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Model API returned HTTP " + response.statusCode());
        }
        JsonNode choice;
        try {
            choice = Json.parseObject(response.body()).path("choices").path(0);
        } catch (IllegalArgumentException invalidJson) {
            throw new IOException("Model API returned invalid JSON", invalidJson);
        }
        var message = choice.path("message");
        if (!message.isObject()) {
            throw new IOException("Model API response has no choices[0].message");
        }
        try {
            var reply = new ChatMessage(message);
            AssistantTurn.parse(reply);
            return reply;
        } catch (IllegalArgumentException invalidMessage) {
            throw new IOException("Invalid model response (finish_reason=" + choice.path("finish_reason").asText("unknown")
                    + "): " + invalidMessage.getMessage(), invalidMessage);
        }
    }
}
