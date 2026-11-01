package io.memoryos.api.meeting.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingSpeakerRequest", description = "A blank or absent name restores the automatic label")
public record MeetingSpeakerRequest(@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true, maxLength = 200)
                                    @Nullable String name) {}
