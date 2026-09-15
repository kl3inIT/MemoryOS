package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ModelCatalogService;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "AvailableModel")
public record AvailableChatModelResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID providerId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String providerName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String modelName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatModelCapabilitiesResponse capabilities,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int contextWindow,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int maxOutputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable ChatModelPricingResponse pricing,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isDefault
) {
    public static AvailableChatModelResponse from(ModelCatalogService.AvailableModel value) {
        return new AvailableChatModelResponse(value.id(), value.providerId(), value.providerName(), value.modelName(),
                value.displayName(), ChatModelCapabilitiesResponse.from(value.capabilities()), value.contextWindow(),
                value.maxOutputTokens(), value.pricing() == null ? null : ChatModelPricingResponse.from(value.pricing()), value.isDefault());
    }
}
