package dev.codingagent.tools.bash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Drains both pipes while retaining at most one shared byte limit. */
public final class LimitedOutput {
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private int remaining;
    private boolean truncated;
    private IOException readFailure;

    public LimitedOutput(int limit) {
        remaining = limit;
    }

    public void capture(InputStream stream, boolean errorStream) {
        try (stream) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                append(errorStream, buffer, count);
            }
        } catch (IOException exception) {
            synchronized (this) {
                readFailure = exception;
            }
        }
    }

    private synchronized void append(boolean errorStream, byte[] bytes, int count) {
        int retained = Math.min(remaining, count);
        (errorStream ? stderr : stdout).write(bytes, 0, retained);
        remaining -= retained;
        truncated |= retained < count;
    }

    public synchronized String stdout() {
        return decode(stdout.toByteArray(), stdout.size());
    }

    public synchronized String stderr() {
        return decode(stderr.toByteArray(), stderr.size());
    }

    public synchronized boolean truncated() {
        return truncated;
    }

    public synchronized IOException readFailure() {
        return readFailure;
    }

    public static String decode(byte[] bytes, int length) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalStateException("Cannot decode tool output", exception);
        }
    }
}
