package io.memoryos.tenant;

import java.util.Objects;

public record TenantSessionAuthority(
        TenantId tenantId,
        String tenantDisplayName,
        TenantMembershipRole role
) {

    public TenantSessionAuthority {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(tenantDisplayName, "tenantDisplayName must not be null");
        if (tenantDisplayName.isBlank()) {
            throw new IllegalArgumentException("tenantDisplayName must not be blank");
        }
        Objects.requireNonNull(role, "role must not be null");
    }

}
