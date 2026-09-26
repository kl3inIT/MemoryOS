package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MeetingWordCorrectionRequest", description = "What was said at one marked stretch of a line")
public record MeetingWordCorrectionRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int start,
                                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int end,
                                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2000) String text) {}
