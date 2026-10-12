package io.memoryos.chat.streaming;

import java.util.Objects;
import java.util.UUID;

/** A chunk of one research agent's report, with the agent's own citation numbers. */
public record IntermediateReport(UUID assistantMessageId, long sequence,
        String toolCallId, String text) implements ChatStreamEvent {
    public static final String TYPE = "intermediate_report";

    public IntermediateReport {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(text, "text");
    }

    @Override
    public String type() {
        return TYPE;
    }
}
