package dev.codingagent.model;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

public record ModelConfig(URI baseUrl, String model, String apiKey, Duration timeout) {
    public ModelConfig {
        if (baseUrl == null || baseUrl.getHost() == null
                || !("https".equals(baseUrl.getScheme()) || "http".equals(baseUrl.getScheme()))) {
            throw new IllegalArgumentException("Model base URL must use HTTP or HTTPS");
        }
        if (model == null || model.isBlank() || apiKey == null
                || timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Invalid model configuration");
        }
    }

    public static ModelConfig fromEnvironment(Map<String, String> environment) {
        return new ModelConfig(URI.create(required(environment, "OPENAI_BASE_URL")),
                required(environment, "OPENAI_MODEL"), environment.getOrDefault("OPENAI_API_KEY", ""),
                timeout(environment.getOrDefault("OPENAI_TIMEOUT_SECONDS", "60")));
    }

    public URI endpoint() {
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/") ? base : base + "/") + "chat/completions");
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Set " + name + " or use --script for a model-free demo");
        }
        return value;
    }

    private static Duration timeout(String value) {
        try {
            long seconds = Long.parseLong(value);
            if (seconds > 0) {
                return Duration.ofSeconds(seconds);
            }
        } catch (NumberFormatException invalid) {
            // Report the setting name instead of exposing a parsing implementation detail.
        }
        throw new IllegalArgumentException("OPENAI_TIMEOUT_SECONDS must be a positive integer");
    }

    @Override
    public String toString() {
        return "ModelConfig[baseUrl=" + baseUrl + ", model=" + model + ", apiKey=<redacted>, timeout=" + timeout + "]";
    }
}
