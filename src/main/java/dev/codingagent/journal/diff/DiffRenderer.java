package dev.codingagent.journal.diff;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Objects;

import dev.codingagent.journal.snapshot.PathImage;

/** A whole-file text diff with exact hashes. Binary files and directory changes get summaries. */
public final class DiffRenderer {
    private DiffRenderer() {
    }

    public static String render(Collection<PathChange> changes) {
        var out = new StringBuilder();
        changes.stream().sorted(java.util.Comparator.comparing(PathChange::path)).forEach(change -> {
            if (Objects.equals(change.before(), change.after())) {
                return;
            }
            out.append("diff -- ").append(change.path()).append('\n');
            out.append("before: ").append(describe(change.before())).append('\n');
            out.append("after:  ").append(describe(change.after())).append('\n');
            String before = text(change.before());
            String after = text(change.after());
            if (before == null || after == null) {
                out.append("Binary or directory change; exact images are saved in the journal.\n");
                return;
            }
            out.append("--- ").append(change.before() == null ? "/dev/null" : "a/" + change.path()).append('\n');
            out.append("+++ ").append(change.after() == null ? "/dev/null" : "b/" + change.path()).append('\n');
            before.lines().forEach(line -> out.append('-').append(line).append('\n'));
            after.lines().forEach(line -> out.append('+').append(line).append('\n'));
        });
        return out.isEmpty() ? "No content changes.\n" : out.toString();
    }

    private static String describe(PathImage image) {
        return image == null ? "absent" : image.directory() ? "directory" : "SHA-256 " + image.sha256();
    }

    private static String text(PathImage image) {
        if (image == null) {
            return "";
        }
        if (image.directory()) {
            return null;
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(image.bytes())).toString();
            return text.contains("\u0000") ? null : text;
        } catch (CharacterCodingException binary) {
            return null;
        }
    }
}
