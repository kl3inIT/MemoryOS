package io.memoryos.iam.tenant.bootstrap;

import java.util.Objects;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;

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
