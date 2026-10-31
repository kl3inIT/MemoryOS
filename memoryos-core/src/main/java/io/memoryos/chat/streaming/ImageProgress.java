package io.memoryos.chat.streaming;

import io.memoryos.chat.ChatImageEvent;
import java.util.Objects;
import java.util.UUID;

/** Progress of one image generation. */
public record ImageProgress(UUID assistantMessageId, long sequence, ChatImageEvent image) implements ChatStreamEvent {
    public static final String TYPE = "image";

    public ImageProgress {
        Objects.requireNonNull(image, "image");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
