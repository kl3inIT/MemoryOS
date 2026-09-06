package io.memoryos.iam;

import java.util.Objects;

public record IamAccess(TenantId tenantId, Authority authority) {

    public IamAccess {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(authority, "authority must not be null");
    }
}
