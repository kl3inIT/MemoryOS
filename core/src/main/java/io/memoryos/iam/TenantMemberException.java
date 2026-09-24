package io.memoryos.iam;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

public final class TenantMemberException extends BusinessException {

    private TenantMemberException(
            String code,
            FailureCategory category,
            String safeMessage,
            String diagnosticMessage
    ) {
        super(code, category, safeMessage, diagnosticMessage);
    }


    public static TenantMemberException notFound() {
        return new TenantMemberException(
                "TENANT_MEMBER_NOT_FOUND",
                FailureCategory.NOT_FOUND,
                "The Tenant member was not found.",
                "Tenant member target was not found in the administrator's Tenant"
        );
    }

    public static TenantMemberException ownerProtected() {
        return new TenantMemberException(
                "TENANT_MEMBER_OWNER_PROTECTED",
                FailureCategory.NOT_PERMITTED,
                "The Tenant owner cannot be activated or deactivated.",
                "Tenant member command rejected an OWNER target"
        );
    }
}
