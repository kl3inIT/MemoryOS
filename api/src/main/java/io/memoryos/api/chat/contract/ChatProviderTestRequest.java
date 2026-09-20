package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** An unsaved or edited provider connection to check; a kept key on {@code providerId} uses its stored key. */
@Schema(name = "ProviderTestInput")
public record ChatProviderTestRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String adapterType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String baseUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ChatProviderCredentialRequest credential,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) @Nullable UUID providerId
) {
    @Override public @NonNull String toString() { return "ChatProviderTestRequest[redacted]"; }
}
