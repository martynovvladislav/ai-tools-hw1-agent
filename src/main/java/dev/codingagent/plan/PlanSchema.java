package dev.codingagent.plan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.codingagent.json.Json;

/** API output schema; WorkPlan still validates the graph and the meaning of checks. */
public final class PlanSchema {
    private PlanSchema() {
    }

    public static ObjectNode json() throws IOException {
        try (var input = PlanSchema.class.getResourceAsStream("/plan-schema.json")) {
            if (input == null) {
                throw new IOException("Missing plan-schema.json resource");
            }
            return Json.parseObject(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
