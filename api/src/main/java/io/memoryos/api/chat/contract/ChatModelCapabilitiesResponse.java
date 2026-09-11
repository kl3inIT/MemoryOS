package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ModelSettings;

@Schema(name = "Capabilities")
public record ChatModelCapabilitiesResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean streaming,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean toolCalling,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean vision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean reasoning
) {
    public static ChatModelCapabilitiesResponse from(ModelSettings.Capabilities value) {
        return new ChatModelCapabilitiesResponse(value.streaming(), value.toolCalling(), value.vision(), value.reasoning());
    }
}
