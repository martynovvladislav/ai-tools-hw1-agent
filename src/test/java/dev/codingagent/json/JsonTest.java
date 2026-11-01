package dev.codingagent.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {
    @Test
    void comparesNestedObjectsWithoutDependingOnKeyOrderOrNumberSpelling() {
        assertEquals(Json.object("{\"b\":{\"y\":1.00,\"x\":2},\"a\":[3,4]}"),
                Json.object("{\"a\":[3.0,4],\"b\":{\"x\":2.0,\"y\":1}}"));
    }

    @Test
    void preservesArrayOrderAndStringContent() {
        assertNotEquals(Json.object("{\"a\":[1,2]}"), Json.object("{\"a\":[2,1]}"));
        assertNotEquals(Json.object("{\"a\":\"1\"}"), Json.object("{\"a\":1}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{", "{} {}", "{\"x\":1,\"x\":2}"})
    void rejectsAmbiguousOrInvalidArgumentObjects(String json) {
        assertThrows(IllegalArgumentException.class, () -> Json.object(json));
    }
}
