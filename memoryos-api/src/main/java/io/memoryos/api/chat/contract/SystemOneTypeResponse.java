package io.memoryos.api.chat.contract;

import io.memoryos.ai.systemone.SystemOneCapabilities;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.ai.systemone.SystemOneProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "SystemOneType", description = "A System One connection type and what a connection of it needs")
public record SystemOneTypeResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SystemOneProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean requiresKey,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "FIXED: no endpoint; URL: a base address; ACCOUNT: an account ID")
        SystemOneCapabilities.Endpoint endpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Empty when the administrator must name one")
        String defaultModel,
        @Schema(description = "The published USD price per million input tokens of the default model") @Nullable Double inputPrice) {
    public static SystemOneTypeResponse from(SystemOneConnectionService.Type type) {
        var capabilities = type.capabilities();
        return new SystemOneTypeResponse(type.provider(), capabilities.requiresKey(), capabilities.endpoint(),
                capabilities.defaultModel(), capabilities.inputPrice());
    }
}
