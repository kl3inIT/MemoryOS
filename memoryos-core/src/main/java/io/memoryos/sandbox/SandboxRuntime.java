package io.memoryos.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Where sandboxes run (MEM-270): what every runtime offers, as agent-sandbox's {@code Sandbox} keeps to what every
 * backend has. A runtime answers {@link SandboxGoneException} for a sandbox that expired or was killed; no runtime
 * type leaves its integration module.
 */
public interface SandboxRuntime {
    /** The value recorded in {@code sandbox_workspaces.runtime}. */
    String name();

    /** Starts a sandbox that expires after {@code spec.timeToLive()} unless renewed; answers its id. */
    String create(SandboxSpec spec);

    /** Moves the sandbox's expiry to {@code timeToLive} from now. */
    void renew(String id, Duration timeToLive);

    SandboxCommandResult run(String id, SandboxCommand command);

    void write(String id, String path, byte[] content);

    /** Writes the file from {@code content}, which the caller closes, streaming where the runtime can. */
    default void write(String id, String path, InputStream content) {
        try {
            write(id, path, content.readAllBytes());
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** At most {@code maxBytes}; a longer file is a {@link IllegalArgumentException}. */
    byte[] read(String id, String path, long maxBytes);

    /** Streams a file of any size; the caller closes it. */
    InputStream open(String id, String path);

    List<SandboxEntry> list(String id, String path, int depth);

    /** Ends the sandbox; one already gone is not an error. */
    void kill(String id);

    /** The sandboxes alive in the runtime that carry every one of {@code labels}, with when each was created. */
    Map<String, Instant> running(Map<String, String> labels);
}
