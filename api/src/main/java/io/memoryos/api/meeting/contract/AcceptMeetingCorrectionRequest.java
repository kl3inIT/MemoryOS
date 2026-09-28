package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

@Schema(name = "AcceptMeetingCorrection")
public record AcceptMeetingCorrectionRequest(
        @Schema(description = "The caller's own wording instead of the model's", nullable = true)
        @Size(max = 2000) @Nullable String text) {}
