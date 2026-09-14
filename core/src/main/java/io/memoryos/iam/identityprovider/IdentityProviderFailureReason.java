package io.memoryos.iam.identityprovider;

import io.memoryos.FailureCategory;
import io.memoryos.FailureReason;

public enum IdentityProviderFailureReason implements FailureReason {
    INVALID(
            "IDP_INVALID",
            FailureCategory.VALIDATION,
            "The identity provider request is invalid."
    ),
    NOT_FOUND(
            "IDP_NOT_FOUND",
            FailureCategory.NOT_FOUND,
            "The identity provider was not found."
    ),
    ALIAS_CONFLICT(
            "IDP_ALIAS_CONFLICT",
            FailureCategory.CONFLICT,
            "An identity provider with this alias already exists."
    ),
    DISCOVERY_FAILED(
            "IDP_DISCOVERY_FAILED",
            FailureCategory.VALIDATION,
            "The issuer did not return a valid OpenID Connect discovery document."
    ),
    PROVIDER_UNAVAILABLE(
            "IDP_PROVIDER_UNAVAILABLE",
            FailureCategory.SERVICE_UNAVAILABLE,
            "The identity provider service is temporarily unavailable."
    );

    private final String code;
    private final FailureCategory category;
    private final String message;

    IdentityProviderFailureReason(String code, FailureCategory category, String message) {
        this.code = code;
        this.category = category;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public FailureCategory category() {
        return category;
    }

    @Override
    public String message() {
        return message;
    }
}
