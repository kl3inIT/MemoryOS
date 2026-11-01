package io.memoryos.api.identityprovider.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

@Schema(name = "UpdateIdentityProviderRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdateIdentityProviderRequest(
        @Nullable
        @Size(max = 128)
        @Pattern(regexp = "[a-z0-9][a-z0-9._-]*", message = "Use lowercase letters, digits, dots, underscores or hyphens.")
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 128,
                description = "New alias; omit to keep the current alias. Renaming recreates the provider and changes the broker redirect URI.")
        String alias,
        @NotBlank
        @Size(max = 200)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
        String displayName,
        @Nullable
        @Size(max = 2048)
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 2048,
                description = "New issuer URL; omit to keep the current issuer. Changing it re-discovers endpoints and relinks future sign-ins.")
        String issuerUrl,
        @NotBlank
        @Size(max = 255)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 255)
        String clientId,
        @Nullable
        @Size(max = 1024)
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, maxLength = 1024,
                description = "Replacement client secret; omit to keep the stored secret.")
        String clientSecret,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean jitAllowed
) {
}
