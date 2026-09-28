package io.memoryos.api.source.contract;

import io.memoryos.connector.SharePointProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Creates a credential or replaces its authentication. The client secret and the PKCS#12 upload are
 * write-only: no response ever returns them.
 */
@Schema(name = "SharePointCredentialRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SharePointCredentialRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotBlank @Size(max = 120) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Directory (tenant) ID as a GUID")
        @NotBlank @Size(max = 64) String directoryId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Application (client) ID as a GUID")
        @NotBlank @Size(max = 64) String clientId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull SharePointProvider.Cloud cloud,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @NotNull SharePointProvider.AuthMethod authMethod,
        @Schema(description = "Client secret Value, required for CLIENT_SECRET") @Size(max = 256) @Nullable String clientSecret,
        @Schema(description = "Base64 PKCS#12 keystore, required for CERTIFICATE") @Size(max = 24_000) @Nullable String certificate,
        @Schema(description = "PKCS#12 password") @Size(max = 256) @Nullable String certificatePassword) {

    @Override public @NonNull String toString() { return "SharePointCredentialRequest[redacted]"; }
}
