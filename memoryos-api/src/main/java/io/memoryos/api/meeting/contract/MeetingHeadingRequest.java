package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.MeetingMinutesDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingHeadingRequest",
        description = "The parts of a biên bản the transcript cannot supply; a blank field prints as an ellipsis")
public record MeetingHeadingRequest(String organization, String parentOrganization, String number, String about, String place,
                                    String opened, String closed, String chair, String chairRole, String secretary,
                                    String secretaryRole, List<String> attendees,
                                    @Schema(description = "Times New Roman, Arial, Calibri or Tahoma; anything else is set in "
                                            + "Times New Roman, which the decree asks for") @Nullable String font) {
    public MeetingMinutesDocument.Heading toHeading() {
        return new MeetingMinutesDocument.Heading(text(organization), text(parentOrganization), text(number),
                text(about), text(place), text(opened), text(closed), text(chair), text(chairRole), text(secretary),
                text(secretaryRole),
                attendees == null ? List.of()
                        : attendees.stream().map(MeetingHeadingRequest::text).toList(), text(font));
    }

    private static String text(@Nullable String value) {
        return value == null ? "" : value;
    }
}
