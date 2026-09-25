package io.memoryos.iam;

import io.memoryos.FailureCategory;
import io.memoryos.FailureReason;

public enum IdentityProvisioningFailureReason implements FailureReason {
    ACCOUNT_CONFLICT(
            "IDENTITY_PROVISIONING_ACCOUNT_CONFLICT",
            FailureCategory.CONFLICT,
            "The invited email conflicts with an existing identity account."
    ),
    PROVIDER_UNAVAILABLE(
            "IDENTITY_PROVISIONING_PROVIDER_UNAVAILABLE",
            FailureCategory.SERVICE_UNAVAILABLE,
            "Identity activation is temporarily unavailable. Try again."
    );

    private final String code;
    private final FailureCategory category;
    private final String message;

    IdentityProvisioningFailureReason(String code, FailureCategory category, String message) {
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
