package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(name = "MeetingUpdateRequest", description = "What the owner fills in once the meeting is under way")
public record MeetingUpdateRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) String title,
                                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Participant names, at most 50")
                                   List<String> participants) {}
