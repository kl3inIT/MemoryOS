package io.memoryos.api.identityprovider.contract;

import io.memoryos.iam.identityprovider.IdentityProviderView;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "IdentityProviderResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record IdentityProviderResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String alias,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String issuer,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String clientId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean enabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean jitAllowed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String brokerRedirectUri
) {

    public static IdentityProviderResponse from(IdentityProviderView view, String issuerBase) {
        return new IdentityProviderResponse(
                view.alias(),
                view.displayName(),
                view.issuer(),
                view.clientId(),
                view.enabled(),
                view.jitAllowed(),
                issuerBase + "/broker/" + view.alias() + "/endpoint"
        );
    }
}
