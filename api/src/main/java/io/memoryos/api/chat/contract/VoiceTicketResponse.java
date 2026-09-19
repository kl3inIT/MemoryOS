package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;

public record VoiceTicketResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ticket,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt) {
    @Override public @NonNull String toString() { return "VoiceTicketResponse[redacted]"; }
}
