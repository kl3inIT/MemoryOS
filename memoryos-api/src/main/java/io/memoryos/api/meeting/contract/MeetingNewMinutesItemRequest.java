package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingNewMinutesItemRequest", description = "A decision or a piece of work the model missed")
public record MeetingNewMinutesItemRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"DECISION", "ACTION"})
                                           Meeting.ItemKind kind,
                                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Size(max = 2000) String text,
                                           @Schema(nullable = true) @Size(max = 200) @Nullable String owner,
                                           @Schema(nullable = true) @Size(max = 100) @Nullable String due) {}
