package io.memoryos.api.chat.contract;

import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.ai.systemone.SystemOneProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "SystemOneConnection")
public record SystemOneConnectionResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SystemOneProvider provider,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String endpoint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String model,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean credentialConfigured,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DataBoundary dataBoundary,
        @Schema(description = "USD per million input tokens; absent when unknown") @Nullable Double inputPrice,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision) {
    public static SystemOneConnectionResponse from(SystemOneConnectionService.View view) {
        return new SystemOneConnectionResponse(view.id(), view.provider(), view.name(), view.endpoint(), view.model(),
                view.credentialConfigured(), view.dataBoundary(), view.inputPrice(), view.revision());
    }
}
