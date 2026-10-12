package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.MeetingException;
import io.memoryos.meeting.MeetingRecordingService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;

@Schema(name = "MeetingRecordingReservation", description = "Where to send the recording, and the meeting as it now reads")
public record MeetingRecordingReservationResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) MeetingDetailResponse meeting,
                                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String method,
                                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String uploadUrl,
                                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> requiredHeaders,
                                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {
    public static MeetingRecordingReservationResponse from(MeetingRecordingService.Reservation reservation) {
        var upload = reservation.upload();
        // Object storage always authorizes a fresh reservation; without one there is nowhere to send the bytes.
        if (upload == null) throw MeetingException.invalid("Storage is not available for a recording.");
        return new MeetingRecordingReservationResponse(MeetingDetailResponse.from(reservation.meeting()), upload.method(),
                upload.uri().toString(), upload.requiredHeaders(), upload.expiresAt());
    }
}
