package dev.codingagent.json;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.type.LogicalType;

import dev.codingagent.run.RunState;

/** JSON parsing, state serialization and stable comparison of tool arguments. */
public final class Json {
    private static final ObjectMapper MAPPER = createMapper();

    private static ObjectMapper createMapper() {
        var mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        for (var shape : List.of(CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean)) {
            mapper.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        }
        return mapper;
    }

    private Json() {
    }

    public static ObjectNode parseObject(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (!(node instanceof ObjectNode object)) {
                throw new IllegalArgumentException("Expected a JSON object");
            }
            return object;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON object", exception);
        }
    }

    public static String object(String json) {
        return write(normalize(parseObject(json)));
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize JSON", exception);
        }
    }

    public static ObjectNode copy(ObjectNode value) {
        if (value == null) {
            throw new IllegalArgumentException("JSON object must not be null");
        }
        return MAPPER.valueToTree(value);
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.treeToValue(parseObject(json), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid " + type.getSimpleName() + ": " + exception.getOriginalMessage(), exception);
        }
    }

    public static RunState readState(String json) {
        ObjectNode node = parseObject(json);
        if (!node.path("formatVersion").isIntegralNumber()
                || node.path("formatVersion").intValue() != RunState.CURRENT_FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported run state format");
        }
        try {
            return MAPPER.treeToValue(node, RunState.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot read run state", exception);
        }
    }

    private static JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            List<String> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(keys::add);
            keys.sort(String::compareTo);
            for (String key : keys) {
                result.set(key, normalize(node.get(key)));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(value -> result.add(normalize(value)));
            return result;
        }
        return node.isNumber() ? DecimalNode.valueOf(node.decimalValue().stripTrailingZeros()) : node;
    }
}
