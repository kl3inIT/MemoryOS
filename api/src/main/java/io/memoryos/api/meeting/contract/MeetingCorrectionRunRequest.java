package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "MeetingCorrectionRunRef")
public record MeetingCorrectionRunRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID runId) {}
