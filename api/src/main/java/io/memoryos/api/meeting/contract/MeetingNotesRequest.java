package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "MeetingNotesRequest")
public record MeetingNotesRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 50000) String notes,
                                  @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision) {}
