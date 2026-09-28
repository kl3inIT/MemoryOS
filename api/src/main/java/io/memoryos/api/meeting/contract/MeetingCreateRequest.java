package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingCreateRequest", description = "What the owner enters before recording")
public record MeetingCreateRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200) String title,
                                   @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Meeting.Kind kind,
                                   @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, allowableValues = {"vi", "en"})
                                   @Nullable String language,
                                   @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, description = "Participant names, at most 50")
                                   @Nullable List<String> participants,
                                   @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true,
                                           description = "Names and terms the speech provider should prefer, at most 100")
                                   @Nullable List<String> terms) {}
