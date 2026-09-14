package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ModelCatalogService;
import java.util.UUID;
import java.util.Set;

@Schema(name = "ProviderView")
public record ChatProviderResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String adapterType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String baseUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isPublic,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<UUID> groupIds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<UUID> personaIds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean credentialConfigured,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long revision
) {
    public static ChatProviderResponse from(ModelCatalogService.ProviderView value) {
        return new ChatProviderResponse(value.id(), value.name(), value.adapterType(), value.baseUrl(), value.enabled(),
                value.isPublic(), value.groupIds(), value.personaIds(), value.credentialConfigured(), value.revision());
    }
}
