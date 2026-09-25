package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingMinutes", description = "What the model made of the meeting once it ended")
public record MeetingMinutesResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.MinutesStatus status,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String failure,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String summary,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "What kind of meeting this was")
                                     String kind,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant generatedAt,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MeetingMinutesItemResponse> decisions,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MeetingMinutesItemResponse> actions,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                             description = "Whether the words standing now are the owner's rather than the model's")
                                     boolean edited,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                             description = "The subjects the meeting moved through, each at the line it began")
                                     List<MeetingMinutesItemResponse> topics) {
    public static MeetingMinutesResponse from(Meeting.Minutes minutes) {
        return new MeetingMinutesResponse(minutes.status(), minutes.failure(), minutes.summary(), minutes.kind(),
                minutes.generatedAt(), minutes.decisions().stream().map(MeetingMinutesItemResponse::from).toList(),
                minutes.actions().stream().map(MeetingMinutesItemResponse::from).toList(), minutes.edited(),
                minutes.topics().stream().map(MeetingMinutesItemResponse::from).toList());
    }
}
