package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "MeetingBookmark",
        description = "A moment the caller marked while the meeting was running. Only they see it.")
public record MeetingBookmarkResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long atMs,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String label) {
    public static MeetingBookmarkResponse from(Meeting.Bookmark bookmark) {
        return new MeetingBookmarkResponse(bookmark.id(), bookmark.atMs(), bookmark.label());
    }
}
