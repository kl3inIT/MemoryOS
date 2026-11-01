package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(name = "MeetingMinutesSummaryRequest")
public record MeetingMinutesSummaryRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Size(max = 20000) String summary) {}
