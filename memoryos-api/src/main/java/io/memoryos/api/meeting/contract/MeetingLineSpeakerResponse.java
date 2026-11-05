package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MeetingLineSpeaker", description = "One line as it now reads, and the voice it now belongs to")
public record MeetingLineSpeakerResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MeetingUtteranceResponse utterance,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The voice the line now belongs to; new to the meeting when the owner gave a new name")
        MeetingSpeakerResponse speaker) {
    public static MeetingLineSpeakerResponse from(Meeting.LineSpeaker moved) {
        return new MeetingLineSpeakerResponse(MeetingUtteranceResponse.from(moved.utterance()),
                MeetingSpeakerResponse.from(moved.speaker()));
    }
}
