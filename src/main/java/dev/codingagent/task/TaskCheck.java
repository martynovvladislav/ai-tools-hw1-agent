package dev.codingagent.task;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A check against a saved, successful tool result, never against a model statement. */
public record TaskCheck(String tool, @JsonInclude(JsonInclude.Include.NON_NULL) String path,
        @JsonInclude(JsonInclude.Include.NON_NULL) String contains) {
    public TaskCheck {
        if (!Set.of("any", "list_files", "read_file", "write_file", "Bash").contains(tool == null ? "" : tool)) {
            throw new IllegalArgumentException("Unsupported task check tool: " + tool);
        }
        if ((path != null && path.isBlank()) || (contains != null && contains.isBlank())) {
            throw new IllegalArgumentException("Check path/contains must not be blank");
        }
        if (tool.equals("any") && (path != null || contains != null)) {
            throw new IllegalArgumentException("The any check has no path or text condition");
        }
        if (tool.equals("write_file") && contains != null) {
            throw new IllegalArgumentException("Verify written content with a read_file check");
        }
    }
}
