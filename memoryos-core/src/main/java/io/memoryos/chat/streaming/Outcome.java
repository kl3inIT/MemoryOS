package io.memoryos.chat.streaming;

import io.memoryos.chat.ChatContextUse;
import io.memoryos.chat.ChatMessage.Status;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** How the reply ended; always the last event of a reply and the marker readers trust. */
public record Outcome(UUID assistantMessageId, long sequence,
        Status status, @Nullable String failureCode, boolean hasArtifacts, @Nullable ChatContextUse context)
        implements ChatStreamEvent {
    public static final String TYPE = "outcome";

    public Outcome(UUID assistantMessageId, long sequence, Status status, @Nullable String failureCode, boolean hasArtifacts) {
        this(assistantMessageId, sequence, status, failureCode, hasArtifacts, null);
    }

    public Outcome {
        Objects.requireNonNull(status, "status");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
