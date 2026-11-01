package dev.codingagent.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.codingagent.json.Json;
import dev.codingagent.support.TestPlans;

class WorkPlanTest {
    @Test
    void roundTripKeepsTheGraphAndOptionalConditions() {
        WorkPlan plan = WorkPlan.parse(TestPlans.JSON);
        assertEquals(plan, WorkPlan.parse(Json.write(plan)));
        var input = Json.parseObject(TestPlans.JSON);
        ((ObjectNode) input.path("steps").get(0)).remove("depends_on");
        assertEquals(plan, WorkPlan.parse(Json.write(input)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"goal_number", "goal_boolean", "goal_null", "steps_object", "steps_empty", "id_missing",
            "tools_string", "tools_number", "dependency_number", "expected_missing", "checks_empty", "path_number",
            "contains_boolean", "tool_unknown", "tool_not_declared", "risks_string", "risks_boolean", "unknown_field"})
    void invalidPlansAreRejectedWithoutScalarCoercion(String kind) {
        var root = Json.parseObject(TestPlans.JSON);
        var step = (ObjectNode) root.path("steps").get(0);
        var expected = (ObjectNode) step.path("expected_result");
        var check = (ObjectNode) expected.path("checks").get(0);
        switch (kind) {
            case "goal_number" -> root.put("goal", 123);
            case "goal_boolean" -> root.put("goal", true);
            case "goal_null" -> root.putNull("goal");
            case "steps_object" -> root.putObject("steps");
            case "steps_empty" -> root.putArray("steps");
            case "id_missing" -> step.remove("id");
            case "tools_string" -> step.put("tools", "read_file");
            case "tools_number" -> step.putArray("tools").add(7);
            case "dependency_number" -> step.putArray("depends_on").add(7);
            case "expected_missing" -> step.remove("expected_result");
            case "checks_empty" -> expected.putArray("checks");
            case "path_number" -> check.put("path", 17);
            case "contains_boolean" -> check.put("contains", true);
            case "tool_unknown" -> check.put("tool", "unknown");
            case "tool_not_declared" -> {
                step.putArray("tools").add("read_file");
                check.put("tool", "write_file");
            }
            case "risks_string" -> root.put("risks", "risk");
            case "risks_boolean" -> root.putArray("risks").add(true);
            case "unknown_field" -> root.put("unexpected", "value");
            default -> throw new AssertionError(kind);
        }
        assertThrows(IllegalArgumentException.class, () -> WorkPlan.parse(Json.write(root)));
    }
}
