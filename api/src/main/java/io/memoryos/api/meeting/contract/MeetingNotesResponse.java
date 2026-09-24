package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MeetingNotes", description = "The owner's notes as stored")
public record MeetingNotesResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String notes,
                                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                           description = "The meeting's revision, which the next save names")
                                   long revision) {
    public static MeetingNotesResponse from(Meeting.Notes notes) {
        return new MeetingNotesResponse(notes.notes(), notes.revision());
    }
}
