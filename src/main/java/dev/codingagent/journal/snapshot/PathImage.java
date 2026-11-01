package dev.codingagent.journal.snapshot;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Exact bytes for files; directories have no content. Symlinks are not supported. */
public record PathImage(boolean directory, @JsonInclude(JsonInclude.Include.NON_NULL) String contentBase64,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sha256) {
    public PathImage {
        if (directory) {
            if (contentBase64 != null || sha256 != null) {
                throw new IllegalArgumentException("Directory image cannot have file content");
            }
        } else if (contentBase64 == null || sha256 == null
                || !hash(Base64.getDecoder().decode(contentBase64)).equals(sha256)) {
            throw new IllegalArgumentException("File snapshot content does not match its SHA-256");
        }
    }

    public static PathImage file(byte[] content) {
        return new PathImage(false, Base64.getEncoder().encodeToString(content), hash(content));
    }

    public static PathImage folder() {
        return new PathImage(true, null, null);
    }

    public byte[] bytes() {
        if (directory) {
            throw new IllegalStateException("Directory has no bytes");
        }
        return Base64.getDecoder().decode(contentBase64);
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
