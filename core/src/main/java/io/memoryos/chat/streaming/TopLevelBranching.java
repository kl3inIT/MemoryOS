package io.memoryos.chat.streaming;

import java.util.UUID;

/** The orchestrator started this many research agents in one cycle. */
public record TopLevelBranching(UUID assistantMessageId, long sequence, int branches) implements ChatStreamEvent {
    public static final String TYPE = "top_level_branching";

    @Override
    public String type() {
        return TYPE;
    }
}
