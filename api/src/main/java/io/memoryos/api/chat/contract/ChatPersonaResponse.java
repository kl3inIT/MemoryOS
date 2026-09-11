package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.persistence.ModelCatalogRepository;
import java.util.UUID;

@Schema(name = "ChatPersona")
public record ChatPersonaResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name
) {
    public static ChatPersonaResponse from(ModelCatalogRepository.PersonaSummary value) {
        return new ChatPersonaResponse(value.id(), value.name());
    }
}
