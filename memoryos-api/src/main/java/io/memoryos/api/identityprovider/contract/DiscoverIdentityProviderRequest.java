package io.memoryos.api.identityprovider.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "DiscoverIdentityProviderRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record DiscoverIdentityProviderRequest(
        @NotBlank
        @Size(max = 2048)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2048)
        String issuerUrl
) {
}
