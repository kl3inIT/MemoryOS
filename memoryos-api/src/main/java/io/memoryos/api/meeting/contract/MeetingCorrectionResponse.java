package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "MeetingCorrection",
        description = "One proposal for one uncertain stretch. Nothing changes until it is accepted.")
public record MeetingCorrectionResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID utteranceId,
                                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID runId,
                                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int start,
                                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int end,
                                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String before,
                                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String after,
                                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason,
                                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double confidence,
                                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double contextFit,
                                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double meaningSafe,
                                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean matchedGlossary,
                                        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                                        Meeting.CorrectionStatus status) {
    public static MeetingCorrectionResponse from(Meeting.Correction correction) {
        return new MeetingCorrectionResponse(correction.id(), correction.utteranceId(), correction.runId(),
                correction.start(), correction.end(), correction.before(), correction.after(),
                correction.reason(), correction.confidence(), correction.contextFit(), correction.meaningSafe(),
                correction.matchedGlossary(), correction.status());
    }
}
