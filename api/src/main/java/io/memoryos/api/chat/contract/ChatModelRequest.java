package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ModelCatalogService;

@Schema(name = "ModelInput")
public record ChatModelRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String modelName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean visible,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatModelSettingsRequest settings
) {
    public ModelCatalogService.ModelInput toInput() {
        return new ModelCatalogService.ModelInput(modelName, displayName, visible, settings == null ? null : settings.toInput());
    }
}
