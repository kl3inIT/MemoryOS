package io.memoryos.iam;

import io.memoryos.FailureCategory;
import io.memoryos.FailureReason;

public enum InvitationFailureReason implements FailureReason {
    NOT_OWNER(
            "INVITATION_NOT_OWNER",
            FailureCategory.NOT_PERMITTED,
            "An active Tenant owner is required."
    ),
    INVALID_EMAIL(
            "INVITATION_INVALID_EMAIL",
            FailureCategory.VALIDATION,
            "Enter a valid email address."
    ),
    CONFLICT(
            "INVITATION_CONFLICT",
            FailureCategory.CONFLICT,
            "The invitation conflicts with existing state."
    ),
    NOT_AVAILABLE(
            "INVITATION_NOT_AVAILABLE",
            FailureCategory.GONE,
            "This invitation is no longer available."
    ),
    EMAIL_NOT_VERIFIED(
            "INVITATION_EMAIL_NOT_VERIFIED",
            FailureCategory.NOT_PERMITTED,
            "Verify the invited email before continuing."
    ),
    EMAIL_MISMATCH(
            "INVITATION_EMAIL_MISMATCH",
            FailureCategory.NOT_PERMITTED,
            "Sign in with the email address that received this invitation."
    ),
    IDENTITY_CONFLICT(
            "INVITATION_IDENTITY_CONFLICT",
            FailureCategory.CONFLICT,
            "This identity already has Tenant authority."
    );

    private final String code;
    private final FailureCategory category;
    private final String message;

    InvitationFailureReason(String code, FailureCategory category, String message) {
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
