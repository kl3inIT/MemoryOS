package io.memoryos.iam.group;

import java.util.Objects;
import io.memoryos.shared.TenantId;

public record IamAccess(TenantId tenantId, Authority authority) {

    public IamAccess {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(authority, "authority must not be null");
    }
}
