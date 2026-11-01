package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingSummary")
public record MeetingSummaryResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Kind kind,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Status status,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int participants,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "End of the last utterance")
                                     long durationMs,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant endedAt,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                             description = "Whether the member recorded it, as opposed to being shared it")
                                     boolean owned) {
    public static MeetingSummaryResponse from(Meeting.Summary summary) {
        return new MeetingSummaryResponse(summary.id(), summary.title(), summary.kind(), summary.status(), summary.participants(),
                summary.durationMs(), summary.createdAt(), summary.endedAt(), summary.owned());
    }
}
