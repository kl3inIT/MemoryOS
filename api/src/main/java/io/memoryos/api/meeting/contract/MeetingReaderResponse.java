package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "MeetingReader", description = "One member, or one Group, the meeting is shared with")
public record MeetingReaderResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.ReaderKind kind,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name) {
    public static MeetingReaderResponse from(Meeting.Reader reader) {
        return new MeetingReaderResponse(reader.kind(), reader.id(), reader.name());
    }
}
