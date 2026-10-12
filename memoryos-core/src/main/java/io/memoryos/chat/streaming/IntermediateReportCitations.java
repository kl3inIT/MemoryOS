package io.memoryos.chat.streaming;

import io.memoryos.chat.ChatResearchEvent;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Maps the citation numbers of one research agent's report to the turn sources they were merged into. */
public record IntermediateReportCitations(UUID assistantMessageId, long sequence,
        String toolCallId, List<ChatResearchEvent.Citation> citations) implements ChatStreamEvent {
    public static final String TYPE = "intermediate_report_citations";

    public IntermediateReportCitations {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(citations, "citations");
        citations = List.copyOf(citations);
    }

    @Override
    public String type() {
        return TYPE;
    }
}
