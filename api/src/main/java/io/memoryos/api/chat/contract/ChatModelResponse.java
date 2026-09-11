package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.persistence.ModelCatalogRepository;
import java.util.UUID;

@Schema(name = "Model")
public record ChatModelResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID tenantId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID providerId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String modelName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean visible,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatModelSettingsResponse settings,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision
) {
    public static ChatModelResponse from(ModelCatalogRepository.Model value) {
        return new ChatModelResponse(value.id(), value.tenantId(), value.providerId(), value.modelName(), value.displayName(),
                value.visible(), ChatModelSettingsResponse.from(value.settings()), value.revision());
    }
}
