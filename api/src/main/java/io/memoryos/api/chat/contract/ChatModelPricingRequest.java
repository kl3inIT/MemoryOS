package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.ai.ModelSettings;
import org.jspecify.annotations.Nullable;

@Schema(name = "PricingInput")
public record ChatModelPricingRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double inputPerMillion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double outputPerMillion,
        @Schema(nullable = true, description = "USD per million input tokens served from the prompt cache; omitted means the input rate")
        @Nullable Double cachedInputPerMillion
) {
    public ModelSettings.Pricing toInput() {
        return new ModelSettings.Pricing(inputPerMillion, outputPerMillion, cachedInputPerMillion);
    }
}
