package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MeetingUtteranceSpan",
        description = "A stretch of the utterance the provider was unsure of, by character offset, half-open.")
public record MeetingUtteranceSpanResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int start,
                                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int end,
                                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double confidence) {
    public static MeetingUtteranceSpanResponse from(Meeting.Span span) {
        return new MeetingUtteranceSpanResponse(span.start(), span.end(), span.confidence());
    }
}
