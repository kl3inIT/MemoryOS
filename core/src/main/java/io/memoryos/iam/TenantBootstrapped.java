package io.memoryos.iam;

import io.memoryos.shared.TenantId;

import java.util.Objects;

/**
 * Published inside the initial-Tenant bootstrap transaction, after the Tenant row is flushed: with {@code created}
 * when the Tenant was just created, and without it when a restart re-verifies the published Tenant. Listeners run
 * in that transaction and provision their per-Tenant defaults idempotently, so a Tenant never commits without them
 * and a Tenant created before a listener existed receives its defaults on the next start.
 */
public record TenantBootstrapped(TenantId tenantId, boolean created) {

    public TenantBootstrapped {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
    }
}
