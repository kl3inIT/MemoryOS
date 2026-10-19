package io.memoryos.chat.streaming;

import io.memoryos.chat.AssistantPatch;
import java.util.UUID;

/**
 * One change the Agent Builder made to the assistant's unsaved form (MEM-280), sent as its tool call completes so the
 * browser applies it and shows the before → after card. It is not stored: a builder conversation is temporary.
 */
public record AssistantChange(UUID assistantMessageId, long sequence, String toolCallId, AssistantPatch change)
        implements ChatStreamEvent {
    public static final String TYPE = "assistant_change";

    @Override
    public String type() {
        return TYPE;
    }
}
