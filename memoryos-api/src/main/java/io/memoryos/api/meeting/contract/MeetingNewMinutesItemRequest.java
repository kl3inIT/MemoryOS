package io.memoryos.api.meeting.contract;

import io.memoryos.meeting.Meeting;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingNewMinutesItemRequest", description = "A decision or a piece of work the model missed")
public record MeetingNewMinutesItemRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"DECISION", "ACTION"})
                                           Meeting.ItemKind kind,
                                           @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Size(max = 2000) String text,
                                           @Schema(nullable = true) @Size(max = 200) @Nullable String owner,
                                           @Schema(nullable = true, description = "A voice of the meeting as the owner, instead of a written name")
                                           @Valid @Nullable MeetingSpeakerRef ownerSpeaker,
                                           @Schema(nullable = true) @Size(max = 100) @Nullable String due) {}
