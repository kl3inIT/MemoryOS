package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@Schema(name = "MeetingSpeakerRef", description = "One voice of the meeting, as its speakers list names it")
public record MeetingSpeakerRef(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull Meeting.Track track,
                                @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
                                @NotNull @Pattern(regexp = "^[0-9A-Za-z_-]{1,16}$") String label) {
    public static MeetingSpeakerRef from(Meeting.SpeakerRef speaker) {
        return new MeetingSpeakerRef(speaker.track(), speaker.label());
    }

    public Meeting.SpeakerRef toSpeaker() {
        return new Meeting.SpeakerRef(track, label);
    }
}
