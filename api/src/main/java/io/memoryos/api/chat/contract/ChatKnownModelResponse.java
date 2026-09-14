package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ChatProviderAdapter;

@Schema(name = "KnownModel")
public record ChatKnownModelResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String modelName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int contextWindow,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int maxOutputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatModelCapabilitiesResponse capabilities,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatModelPricingResponse pricing
) {
    public static ChatKnownModelResponse from(ChatProviderAdapter.KnownModel value) {
        return new ChatKnownModelResponse(value.modelName(), value.contextWindow(), value.maxOutputTokens(),
                ChatModelCapabilitiesResponse.from(value.capabilities()), ChatModelPricingResponse.from(value.pricing()));
    }
}
