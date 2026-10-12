package io.memoryos.chat.streaming;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.UUID;

/**
 * One event of a reply's stream, as it is kept in Redis and sent to the browser. One record per kind, each naming
 * its wire {@code type} once, after Spring AI's {@code MessagePart}: the names are lower case with underscores, and
 * a type this version does not know decodes to {@link UnknownEvent} instead of failing the replay.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", defaultImpl = UnknownEvent.class)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextDelta.class, name = TextDelta.TYPE),
        @JsonSubTypes.Type(value = Reasoning.class, name = Reasoning.TYPE),
        @JsonSubTypes.Type(value = ResearchPlan.class, name = ResearchPlan.TYPE),
        @JsonSubTypes.Type(value = TopLevelBranching.class, name = TopLevelBranching.TYPE),
        @JsonSubTypes.Type(value = ResearchAgentStart.class, name = ResearchAgentStart.TYPE),
        @JsonSubTypes.Type(value = IntermediateReport.class, name = IntermediateReport.TYPE),
        @JsonSubTypes.Type(value = IntermediateReportCitations.class, name = IntermediateReportCitations.TYPE),
        @JsonSubTypes.Type(value = ToolProgress.class, name = ToolProgress.TYPE),
        @JsonSubTypes.Type(value = ImageProgress.class, name = ImageProgress.TYPE),
        @JsonSubTypes.Type(value = CodeRun.class, name = CodeRun.TYPE),
        @JsonSubTypes.Type(value = Outcome.class, name = Outcome.TYPE),
        @JsonSubTypes.Type(value = UnknownEvent.class, name = UnknownEvent.TYPE) })
@JsonIgnoreProperties(ignoreUnknown = true)
public sealed interface ChatStreamEvent
        permits TextDelta, Reasoning, ResearchPlan, TopLevelBranching, ResearchAgentStart, IntermediateReport,
        IntermediateReportCitations, ToolProgress, ImageProgress, CodeRun, Outcome, UnknownEvent {
    UUID assistantMessageId();

    /** Position in the reply, from 1; the Redis entry ID and the browser's resume cursor. */
    long sequence();

    /** The wire name of this kind of event. */
    String type();

    /** The event ID the browser resumes from. */
    default String id() {
        return assistantMessageId() + ":" + sequence();
    }
}
