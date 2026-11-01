package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "MeetingSpeakerSuggestion")
public record MeetingSpeakerSuggestionResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
                                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID utteranceId,
                                               @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double confidence) {}
