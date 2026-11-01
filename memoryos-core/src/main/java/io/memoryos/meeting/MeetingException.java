package io.memoryos.meeting;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/** Safe meeting failures; none of them reveal whether another member's meeting exists. */
public final class MeetingException extends BusinessException {
    private MeetingException(String code, FailureCategory category, String message) {
        super(code, category, message, message);
    }

    public static MeetingException invalid(String message) {
        return new MeetingException("MEETING_INVALID", FailureCategory.VALIDATION, message);
    }

    /** A meeting that does not exist, belongs to another member or another Tenant. */
    public static MeetingException notFound() {
        return new MeetingException("MEETING_NOT_FOUND", FailureCategory.NOT_FOUND, "The meeting is not available.");
    }

    public static MeetingException ended() {
        return new MeetingException("MEETING_ENDED", FailureCategory.CONFLICT, "The meeting has already ended.");
    }

    public static MeetingException conflict() {
        return new MeetingException("MEETING_CONFLICT", FailureCategory.CONFLICT, "The meeting changed; reload it.");
    }

    public static MeetingException tooLong() {
        return new MeetingException("MEETING_TOO_LONG", FailureCategory.VALIDATION,
                "A meeting can record at most five hours per audio track.");
    }
}
