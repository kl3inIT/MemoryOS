package io.memoryos.api.chat.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import io.memoryos.chat.catalog.ProviderCredentials;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;

@Schema(name = "Change")
public record ChatProviderCredentialRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProviderCredentials.Action action,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) @Nullable String value
) {
    public ProviderCredentials.Change toInput() { return new ProviderCredentials.Change(action, value); }
    @Override public @NonNull String toString() { return "ChatProviderCredentialRequest[redacted]"; }
}
