package io.memoryos.chat;

import org.jspecify.annotations.Nullable;

/** Product progress and evidence only; tool arguments and raw results are not streamed. */
public record ChatSearchEvent(String toolCallId, Stage stage, @Nullable ChatSource source) {
    public enum Stage { STARTED, SELECTING, EXPANDING, SOURCE, COMPLETED, FAILED }
    public ChatSearchEvent {
        if (toolCallId == null || toolCallId.isBlank() || toolCallId.length() > 256 || stage == null
                || (stage == Stage.SOURCE) == (source == null)) throw new IllegalArgumentException("Invalid search event");
    }
}
