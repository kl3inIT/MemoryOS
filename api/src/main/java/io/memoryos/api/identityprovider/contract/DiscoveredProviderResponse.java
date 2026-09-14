package io.memoryos.api.identityprovider.contract;

import io.memoryos.iam.keycloak.DiscoveredOidcProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "DiscoveredProviderResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record DiscoveredProviderResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String issuer,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String authorizationUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String tokenUrl,
        @Nullable
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String logoutUrl,
        @Nullable
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String userInfoUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String jwksUrl
) {

    public static DiscoveredProviderResponse from(DiscoveredOidcProvider provider) {
        return new DiscoveredProviderResponse(
                provider.issuer(),
                provider.authorizationUrl(),
                provider.tokenUrl(),
                provider.logoutUrl(),
                provider.userInfoUrl(),
                provider.jwksUrl()
        );
    }
}
