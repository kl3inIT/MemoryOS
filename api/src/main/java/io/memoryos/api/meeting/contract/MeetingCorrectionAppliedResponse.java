package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.MeetingCorrectionService;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MeetingCorrectionApplied",
        description = "What deciding one stretch changed: the one line it rewrote and the proposal as it now stands")
public record MeetingCorrectionAppliedResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                                               MeetingUtteranceResponse utterance,
                                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                                               MeetingCorrectionResponse correction) {
    public static MeetingCorrectionAppliedResponse from(MeetingCorrectionService.Applied applied) {
        return new MeetingCorrectionAppliedResponse(MeetingUtteranceResponse.from(applied.utterance()),
                MeetingCorrectionResponse.from(applied.correction()));
    }
}
