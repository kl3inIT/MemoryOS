package io.memoryos.chat;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Product progress only; provider errors, credentials and raw image bytes are not streamed. */
public record ChatImageEvent(String toolCallId, Stage stage, @Nullable UUID artifactId,
        @Nullable String mediaType, @Nullable String revisedPrompt) {
    public enum Stage { GENERATING, COMPLETED, FAILED }
    public ChatImageEvent {
        if (toolCallId == null || toolCallId.isBlank() || toolCallId.length() > 256 || stage == null
                || (stage == Stage.COMPLETED) != (artifactId != null))
            throw new IllegalArgumentException("Invalid image event");
    }
}
