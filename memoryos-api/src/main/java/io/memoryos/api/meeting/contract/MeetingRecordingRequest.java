package io.memoryos.api.meeting.contract;

import io.memoryos.voice.VoiceProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "MeetingRecordingRequest", description = "Declared before the bytes are uploaded and checked against them afterwards")
public record MeetingRecordingRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String filename,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mediaType,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes,
                                      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Hex SHA-256 of the file")
                                      String sha256,
                                      @Schema(nullable = true, description = "The speech provider to transcribe with; the Tenant's own is used when absent")
                                      @Nullable VoiceProvider provider) {}
