package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.ai.ModelSettings;
import org.jspecify.annotations.Nullable;

@Schema(name = "Pricing")
public record ChatModelPricingResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double inputPerMillion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double outputPerMillion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Double cachedInputPerMillion
) {
    public static ChatModelPricingResponse from(ModelSettings.Pricing value) {
        return new ChatModelPricingResponse(value.inputPerMillion(), value.outputPerMillion(), value.cachedInputPerMillion());
    }
}
