package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingAudio", description = "An uploaded recording being turned into a transcript")
public record MeetingAudioResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.AudioStatus status,
                                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String failure,
                                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String filename,
                                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes,
                                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String provider) {
    public static MeetingAudioResponse from(Meeting.Audio audio) {
        return new MeetingAudioResponse(audio.status(), audio.failure(), audio.filename(), audio.sizeBytes(),
                audio.provider());
    }
}
