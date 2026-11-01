package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingMinutesItemRequest")
public record MeetingMinutesItemRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Size(max = 2000) String text,
                                        @Schema(nullable = true) @Size(max = 200) @Nullable String owner,
                                        @Schema(nullable = true) @Size(max = 100) @Nullable String due) {}
