package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.MeetingCorrectionService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(name = "MeetingCorrectionRun")
public record MeetingCorrectionRunResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID runId,
                                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MeetingCorrectionResponse> corrections) {
    public static MeetingCorrectionRunResponse from(MeetingCorrectionService.Run run) {
        return new MeetingCorrectionRunResponse(run.id(), run.corrections().stream().map(MeetingCorrectionResponse::from).toList());
    }
}
