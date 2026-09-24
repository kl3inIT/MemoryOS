package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "MeetingParticulars", description = "The meeting's name and the people in it, as stored")
public record MeetingParticularsResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> participants,
                                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                                 description = "The meeting's revision, which the next notes save names")
                                         long revision) {
    public MeetingParticularsResponse {
        participants = List.copyOf(participants);
    }

    public static MeetingParticularsResponse from(Meeting.Particulars particulars) {
        return new MeetingParticularsResponse(particulars.title(), particulars.participants(),
                particulars.revision());
    }
}
