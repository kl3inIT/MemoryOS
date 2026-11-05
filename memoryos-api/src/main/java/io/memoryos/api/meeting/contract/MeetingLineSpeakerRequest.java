package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingLineSpeakerRequest",
        description = "Who spoke one line: a voice of the meeting or a new name, exactly one of them")
public record MeetingLineSpeakerRequest(
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true,
                description = "A voice of the meeting; one of the other track lends only its name")
        @Valid @Nullable MeetingSpeakerRef speaker,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 200,
                description = "A name; the line takes the voice of its track with that name, or a new one")
        @Size(max = 200) @Nullable String name) {}
