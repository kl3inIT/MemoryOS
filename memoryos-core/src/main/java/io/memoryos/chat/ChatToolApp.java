package io.memoryos.chat;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * The MCP Apps view of one tool call (MEM-275): the server and {@code ui://} resource that show it, and the call's
 * arguments and whole result as JSON, which the view receives. Kept beside the answer for its owner's own screen, not
 * replayed to the model; reading it back checks again that the owner can still use the server.
 */
public record ChatToolApp(String callId, UUID serverId, String resourceUri, String input, String output) {
    /** The bound of the input and of the result, as the column checks enforce; a larger call shows no view. */
    public static final int FIELD_BYTES = 65_536;

    public ChatToolApp {
        Objects.requireNonNull(serverId, "serverId");
        if (!ChatToolEvent.validId(callId) || resourceUri == null || !resourceUri.startsWith("ui://")
                || resourceUri.length() > 1024 || !fits(input) || !fits(output))
            throw new IllegalArgumentException("Invalid tool view");
    }

    /** Whether a JSON field fits the stored bound. */
    public static boolean fits(String json) {
        return json != null && json.getBytes(StandardCharsets.UTF_8).length <= FIELD_BYTES;
    }

    @Override public @NonNull String toString() { return "ChatToolApp[" + callId + "]"; }
}
