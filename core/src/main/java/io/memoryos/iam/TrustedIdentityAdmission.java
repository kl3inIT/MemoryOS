package io.memoryos.iam;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;

/** Browser admission after the composition root has verified the trusted identity-provider claim. */
public interface TrustedIdentityAdmission {

    ActorId admit(TenantId tenantId, ExternalIdentity identity);
}
