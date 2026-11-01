package io.memoryos.iam;

import java.util.Objects;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;

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
