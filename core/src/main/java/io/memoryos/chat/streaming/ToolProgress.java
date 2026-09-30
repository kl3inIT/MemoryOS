package io.memoryos.chat.streaming;

import io.memoryos.chat.ChatToolEvent;
import java.util.Objects;
import java.util.UUID;

/** Progress of one tool call as the person sees it: a stage, never the tool's arguments or raw result. */
public record ToolProgress(UUID assistantMessageId, long sequence, ChatToolEvent tool) implements ChatStreamEvent {
    public static final String TYPE = "tool";

    public ToolProgress {
        Objects.requireNonNull(tool, "tool");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
