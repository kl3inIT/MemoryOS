package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.MeetingMinutesDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "MeetingHeading", description = "The biên bản heading as the owner last saved it")
public record MeetingHeadingResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                             description = "Whether the owner saved one for this meeting. Otherwise only the "
                                                     + "organization, its parent and the typeface are filled, carried from "
                                                     + "the caller's last biên bản") boolean saved,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String organization,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String parentOrganization,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String number,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String about,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String place,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String opened,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String closed,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String chair,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String chairRole,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String secretary,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String secretaryRole,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> attendees,
                                     @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String font) {
    public static MeetingHeadingResponse from(MeetingMinutesDocument.Heading heading) {
        return from(heading, true);
    }

    public static MeetingHeadingResponse from(MeetingMinutesDocument.Heading heading, boolean saved) {
        return new MeetingHeadingResponse(saved, heading.organization(), heading.parentOrganization(), heading.number(),
                heading.about(), heading.place(), heading.opened(), heading.closed(), heading.chair(),
                heading.chairRole(), heading.secretary(), heading.secretaryRole(), heading.attendees(),
                heading.font());
    }

    public static MeetingHeadingResponse none() {
        return new MeetingHeadingResponse(false, "", "", "", "", "", "", "", "", "", "", "", List.of(), "");
    }
}
