package io.memoryos.api.meeting.contract;

import io.memoryos.voice.BatchTranscriptionService;
import io.memoryos.voice.VoiceProvider;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MeetingTranscriber", description = "A speech connection a recording may be transcribed with")
public record MeetingTranscriberResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) VoiceProvider provider,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String model,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                                 description = "Whether it separates the speakers of a recording") boolean diarizes,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                                 description = "The largest recording this provider accepts") long maxBytes,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                                 description = "Whether it is the Tenant's own choice") boolean selected) {
    public static MeetingTranscriberResponse from(BatchTranscriptionService.Transcriber transcriber) {
        return new MeetingTranscriberResponse(transcriber.provider(), transcriber.model(), transcriber.diarizes(),
                transcriber.maxBytes(), transcriber.selected());
    }
}
