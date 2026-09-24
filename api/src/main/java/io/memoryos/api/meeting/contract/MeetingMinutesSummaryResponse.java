package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MeetingMinutesSummary", description = "The summary of the minutes as it now reads")
public record MeetingMinutesSummaryResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String summary,
                                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                                    description = "Whether the words standing now are the owner's rather than the model's")
                                            boolean edited) {
    public static MeetingMinutesSummaryResponse from(Meeting.MinutesSummary summary) {
        return new MeetingMinutesSummaryResponse(summary.summary(), summary.edited());
    }
}
