package io.memoryos.tenant;

import io.memoryos.identity.ActorId;

import java.util.Objects;

public record InitialTenantBootstrapResult(
        ActorId ownerActorId,
        TenantId tenantId,
        boolean created
) {

    public InitialTenantBootstrapResult {
        Objects.requireNonNull(ownerActorId, "ownerActorId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
    }
}
