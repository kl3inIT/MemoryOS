package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ModelSettings;

@Schema(name = "CapabilitiesInput")
public record ChatModelCapabilitiesRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean streaming,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean toolCalling,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean vision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reasoning
) {
    public ModelSettings.Capabilities toInput() {
        return new ModelSettings.Capabilities(streaming, toolCalling, vision, reasoning);
    }
}
