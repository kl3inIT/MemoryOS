package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ModelCatalogService;
import java.util.UUID;
import java.util.Set;
import org.jspecify.annotations.NonNull;

@Schema(name = "ProviderInput")
public record ChatProviderRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String adapterType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String baseUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean isPublic,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<UUID> groupIds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Set<UUID> personaIds,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatProviderCredentialRequest credential
) {
    public ModelCatalogService.ProviderInput toInput() {
        return new ModelCatalogService.ProviderInput(name, adapterType, baseUrl, enabled, isPublic, groupIds, personaIds,
                credential == null ? null : credential.toInput());
    }
    @Override public @NonNull String toString() { return "ChatProviderRequest[redacted]"; }
}
