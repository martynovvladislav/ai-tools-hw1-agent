package dev.codingagent.model;

/** Untrusted arguments are kept verbatim until the tool registry validates them. */
public record ToolRequest(String id, String name, String argumentsJson) {
    public ToolRequest {
        if (id == null || id.isBlank() || name == null || name.isBlank() || argumentsJson == null) {
            throw new IllegalArgumentException("Invalid tool request envelope");
        }
    }
}
