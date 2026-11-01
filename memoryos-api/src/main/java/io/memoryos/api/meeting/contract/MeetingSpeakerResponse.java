package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingSpeaker")
public record MeetingSpeakerResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Track track,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String name,
                                     @Schema(description = "The name this voice gave itself, offered to the owner",
                                             requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
                                     @Nullable MeetingSpeakerSuggestionResponse suggestion) {
    public static MeetingSpeakerResponse from(Meeting.Speaker speaker) {
        var suggestion = speaker.suggestion();
        return new MeetingSpeakerResponse(speaker.track(), speaker.label(), speaker.name(),
                suggestion == null ? null : new MeetingSpeakerSuggestionResponse(suggestion.name(),
                        suggestion.utteranceId(), suggestion.confidence()));
    }
}
