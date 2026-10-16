package io.memoryos.api.chat.contract;

import io.memoryos.chat.PersonaSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "ChatPersona")
public record ChatPersonaResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name
) {
    public static ChatPersonaResponse from(PersonaSummary value) {
        return new ChatPersonaResponse(value.id(), value.name());
    }
}
