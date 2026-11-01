package io.memoryos.api.identityprovider.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(name = "CreateIdentityProviderRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreateIdentityProviderRequest(
        @NotBlank
        @Size(max = 128)
        @Pattern(regexp = "[a-z0-9][a-z0-9._-]*", message = "Use lowercase letters, digits, dots, underscores or hyphens.")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 128)
        String alias,
        @NotBlank
        @Size(max = 200)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 200)
        String displayName,
        @NotBlank
        @Size(max = 2048)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2048)
        String issuerUrl,
        @NotBlank
        @Size(max = 255)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 255)
        String clientId,
        @NotBlank
        @Size(max = 1024)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 1024)
        String clientSecret,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean jitAllowed
) {
}
