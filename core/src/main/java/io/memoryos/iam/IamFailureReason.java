package io.memoryos.iam;

import io.memoryos.FailureCategory;
import io.memoryos.FailureReason;

public enum IamFailureReason implements FailureReason {
    ACCESS_DENIED(
            "IAM_ACCESS_DENIED",
            FailureCategory.NOT_PERMITTED,
            "You do not have permission to perform this action."
    ),
    GROUP_NOT_FOUND(
            "IAM_GROUP_NOT_FOUND",
            FailureCategory.NOT_FOUND,
            "The group was not found."
    ),
    GROUP_CONFLICT(
            "IAM_GROUP_CONFLICT",
            FailureCategory.CONFLICT,
            "A group with this name already exists."
    ),
    GROUP_INVALID(
            "IAM_GROUP_INVALID",
            FailureCategory.VALIDATION,
            "The group request is invalid."
    ),
    GROUP_PROTECTED(
            "IAM_GROUP_PROTECTED",
            FailureCategory.NOT_PERMITTED,
            "This system group operation is protected."
    ),
    GROUP_MEMBER_NOT_FOUND(
            "IAM_GROUP_MEMBER_NOT_FOUND",
            FailureCategory.NOT_FOUND,
            "The group member was not found."
    ),
    LAST_ADMIN_PROTECTED(
            "IAM_LAST_ADMIN_PROTECTED",
            FailureCategory.CONFLICT,
            "The final active administrator cannot be removed or deactivated."
    ),
    CONFIGURED_OWNER_PROTECTED(
            "IAM_CONFIGURED_OWNER_PROTECTED",
            FailureCategory.NOT_PERMITTED,
            "The configured owner cannot be removed or deactivated."
    ),
    MANAGER_AMPLIFICATION_DENIED(
            "IAM_MANAGER_AMPLIFICATION_DENIED",
            FailureCategory.NOT_PERMITTED,
            "A scoped manager cannot delegate permissions they do not hold."
    );

    private final String code;
    private final FailureCategory category;
    private final String message;

    IamFailureReason(String code, FailureCategory category, String message) {
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
