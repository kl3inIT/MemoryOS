package io.memoryos.api.chat.contract;

import io.memoryos.library.LibraryEntry;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ChatLibraryEntryMeeting", description = "What a meeting row offers: its biên bản and its transcript")
public record ChatLibraryEntryMeetingResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"RECORDING", "TRANSCRIBING", "ENDED"})
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The minutes are written") boolean minutesReady,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Somebody spoke, so a transcript exists")
        boolean hasTranscript,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Milliseconds covered by the transcript")
        long durationMs
) {

    public static ChatLibraryEntryMeetingResponse from(LibraryEntry.MeetingState meeting) {
        return new ChatLibraryEntryMeetingResponse(meeting.status(), meeting.minutesReady(), meeting.hasTranscript(),
                meeting.durationMs());
    }
}
