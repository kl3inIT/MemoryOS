package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ModelSettings;
import org.jspecify.annotations.Nullable;
import java.util.Map;

@Schema(name = "ModelSettings")
public record ChatModelSettingsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int contextWindow,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int maxOutputTokens,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatModelCapabilitiesResponse capabilities,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, Object> options,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"object", "null"})
        @Nullable ChatModelPricingResponse pricing,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String tokenizerProfile
) {
    public static ChatModelSettingsResponse from(ModelSettings value) {
        return new ChatModelSettingsResponse(value.contextWindow(), value.maxOutputTokens(),
                ChatModelCapabilitiesResponse.from(value.capabilities()), value.options(),
                value.pricing() == null ? null : ChatModelPricingResponse.from(value.pricing()), value.tokenizerProfile());
    }
}
