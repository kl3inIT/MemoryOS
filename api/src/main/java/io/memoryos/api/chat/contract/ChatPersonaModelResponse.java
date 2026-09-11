package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.persistence.ModelCatalogRepository;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "PersonaModel")
public record ChatPersonaModelResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID personaId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable UUID modelConfigurationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision
) {
    public static ChatPersonaModelResponse from(ModelCatalogRepository.PersonaModel value) {
        return new ChatPersonaModelResponse(value.personaId(), value.modelConfigurationId(), value.revision());
    }
}
