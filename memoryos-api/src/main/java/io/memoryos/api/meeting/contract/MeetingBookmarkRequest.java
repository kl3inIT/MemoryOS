package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingBookmarkRequest")
public record MeetingBookmarkRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                                             description = "Milliseconds from the start of the recording") long atMs,
                                     @Schema(description = "What to call it; a number is used when this is left out",
                                             nullable = true) @Size(max = 200) @Nullable String label) {}
