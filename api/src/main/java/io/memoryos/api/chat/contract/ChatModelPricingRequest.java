package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ModelSettings;

@Schema(name = "PricingInput")
public record ChatModelPricingRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double inputPerMillion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double outputPerMillion
) {
    public ModelSettings.Pricing toInput() {
        return new ModelSettings.Pricing(inputPerMillion, outputPerMillion);
    }
}
