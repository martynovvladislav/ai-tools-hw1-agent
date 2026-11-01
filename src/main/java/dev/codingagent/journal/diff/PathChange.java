package dev.codingagent.journal.diff;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.codingagent.journal.snapshot.PathImage;

/** Null represents absence. Aggregate entries may have equal endpoints but retain ownership. */
public record PathChange(String path, @JsonInclude(JsonInclude.Include.NON_NULL) PathImage before,
        @JsonInclude(JsonInclude.Include.NON_NULL) PathImage after) {
}
