package io.memoryos.iam;

import java.util.Objects;

import org.springframework.util.Assert;

/**
 * An actor's active membership in an active Tenant, projected for presentation and authority checks.
 */
public record TenantMembership(
        TenantId tenantId,
        String tenantDisplayName,
        TenantMembershipRole role
) {

    public TenantMembership {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Assert.hasText(tenantDisplayName, "tenantDisplayName must not be blank");
        Objects.requireNonNull(role, "role must not be null");
    }
}
