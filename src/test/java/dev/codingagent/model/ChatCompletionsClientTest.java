package dev.codingagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.codingagent.json.Json;
import dev.codingagent.plan.PlanSchema;
import dev.codingagent.plan.WorkPlan;
import dev.codingagent.support.ModelReplies;
import dev.codingagent.support.TestPlans;
import dev.codingagent.tools.ToolDefinition;

class ChatCompletionsClientTest {
    @Test
    void structuredPlanUsesTheOutputSchemaWithoutToolCalling() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        var plan = Json.parseObject(TestPlans.JSON);
        ((ObjectNode) plan.path("steps").path(0).path("expected_result").path("checks").path(0)).put("tool", "read_file");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"choices\":[{\"message\":" + ModelReplies.text(Json.write(plan)).json() + "}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var client = new ChatCompletionsClient(config(server));
            var response = client.generateStructured(List.of(ChatMessage.user("Return the plan")), PlanSchema.json());
            assertEquals(WorkPlan.parse(Json.write(plan)), WorkPlan.parse(AssistantTurn.parse(response).text()));
            var body = Json.parseObject(request.get());
            assertFalse(body.has("tools"));
            assertFalse(body.has("tool_choice"));
            assertEquals("json_schema", body.path("response_format").path("type").asText());
            assertEquals(PlanSchema.json(), body.path("response_format").path("json_schema").path("schema"));
            client.generate(List.of(ChatMessage.user("Execute the approved task")),
                    List.of(new ToolDefinition("read_file", "Read", List.of("path"), false, true)));
            var execution = Json.parseObject(request.get());
            assertFalse(execution.has("response_format"));
            assertEquals("read_file", execution.path("tools").path(0).path("function").path("name").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsSchemasHistoryAndAuthorizationAndPreservesTheAssistantMessage() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var fullReply = (ObjectNode) ModelReplies.calls(
                new ToolRequest("call-1", "read_file", "{\"path\":\"order.py\"}")).json();
        fullReply.put("reasoning_content", "preserve provider fields").putObject("provider_extra").put("tokens", 17);
        var expected = new ChatMessage(fullReply);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = ("{\"choices\":[{\"message\":" + expected.json() + "}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var config = config(server);
            var priorPayload = (ObjectNode) ModelReplies.text("I will inspect the files").json();
            priorPayload.put("reasoning_content", "prior reasoning").put("reasoning", "other reasoning")
                    .put("thinking", "private thinking").put("provider_extra", "keep this field");
            var prior = new ChatMessage(priorPayload);
            var response = new ChatCompletionsClient(config).generate(List.of(ChatMessage.user("Read code"), prior),
                    List.of(new ToolDefinition("read_file", "Read", List.of("path"), false, true)));
            assertEquals(expected, response);
            assertEquals("call-1", AssistantTurn.parse(response).calls().getFirst().id());
            var body = Json.parseObject(request.get());
            assertEquals("test-model", body.path("model").asText());
            assertEquals("auto", body.path("tool_choice").asText());
            assertEquals("Read code", body.path("messages").path(0).path("content").asText());
            var sentPrior = body.path("messages").path(1);
            assertFalse(sentPrior.has("reasoning_content"));
            assertFalse(sentPrior.has("reasoning"));
            assertFalse(sentPrior.has("thinking"));
            assertEquals("I will inspect the files", sentPrior.path("content").asText());
            assertEquals("keep this field", sentPrior.path("provider_extra").asText());
            assertEquals(priorPayload, prior.json());
            assertEquals("object", body.path("tools").path(0).path("function").path("parameters").path("type").asText());
            assertEquals("Bearer test-key", authorization.get());
            assertFalse(request.get().contains("test-key"));
            assertFalse(config.toString().contains("test-key"));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "stop"})
    void reasoningOnlyReplyReportsFinishReasonWithoutExposingReasoningOrRetrying(String finishReason) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            byte[] response = ("{\"choices\":[{\"finish_reason\":\"" + finishReason
                    + "\",\"message\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning\":\"private details\"}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            IOException error = assertThrows(IOException.class, () -> new ChatCompletionsClient(config(server))
                    .generate(List.of(ChatMessage.user("Task")), List.of()));
            assertTrue(error.getMessage().contains("finish_reason=" + finishReason));
            assertFalse(error.getMessage().contains("private details"));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void httpFailureDoesNotSilentlyRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var client = new ChatCompletionsClient(config(server));
            IOException error = assertThrows(IOException.class,
                    () -> client.generate(List.of(ChatMessage.user("Task")), List.of()));
            assertTrue(error.getMessage().contains("503"));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static ModelConfig config(HttpServer server) {
        return new ModelConfig(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-model", "test-key", Duration.ofSeconds(5));
    }
}
