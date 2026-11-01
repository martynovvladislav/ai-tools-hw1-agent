package dev.codingagent.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ModelConfigTest {
    @Test
    void defaultTimeoutAndOptionalKeyWorkForALocalServer() {
        var config = ModelConfig.fromEnvironment(Map.of("OPENAI_BASE_URL", "http://localhost:11434/v1",
                "OPENAI_MODEL", "qwen3:4b"));
        assertEquals(Duration.ofSeconds(60), config.timeout());
        assertEquals("", config.apiKey());
        assertEquals("http://localhost:11434/v1/chat/completions", config.endpoint().toString());
    }

    @Test
    void localModelCanUseALongerRequestTimeout() {
        var config = ModelConfig.fromEnvironment(Map.of("OPENAI_BASE_URL", "http://localhost:11434/v1/",
                "OPENAI_MODEL", "qwen3:4b", "OPENAI_TIMEOUT_SECONDS", "300"));
        assertEquals(Duration.ofSeconds(300), config.timeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "", "abc", "1.5"})
    void invalidTimeoutFailsWithTheSettingName(String value) {
        var error = assertThrows(IllegalArgumentException.class, () -> ModelConfig.fromEnvironment(Map.of(
                "OPENAI_BASE_URL", "http://localhost:11434/v1/", "OPENAI_MODEL", "qwen3:4b",
                "OPENAI_TIMEOUT_SECONDS", value)));
        assertTrue(error.getMessage().contains("OPENAI_TIMEOUT_SECONDS"));
    }
}
