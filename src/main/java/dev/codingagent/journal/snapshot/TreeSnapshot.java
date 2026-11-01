package dev.codingagent.journal.snapshot;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import dev.codingagent.json.Json;

public record TreeSnapshot(Map<String, PathImage> paths) {
    public TreeSnapshot {
        paths = Collections.unmodifiableMap(new TreeMap<>(paths));
    }

    public String fingerprint() {
        return PathImage.hash(Json.write(this).getBytes(StandardCharsets.UTF_8));
    }
}
