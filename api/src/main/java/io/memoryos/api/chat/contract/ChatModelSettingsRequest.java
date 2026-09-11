package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ModelSettings;
import org.jspecify.annotations.Nullable;
import java.util.Map;

@Schema(name = "ModelSettingsInput")
public record ChatModelSettingsRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "256", maximum = "10000000") int contextWindow,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int maxOutputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatModelCapabilitiesRequest capabilities,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, Object> options,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) @Nullable ChatModelPricingRequest pricing,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1) String tokenizerProfile
) {
    public ModelSettings toInput() {
        return new ModelSettings(contextWindow, maxOutputTokens, capabilities == null ? null : capabilities.toInput(),
                options, pricing == null ? null : pricing.toInput(), tokenizerProfile);
    }
}
