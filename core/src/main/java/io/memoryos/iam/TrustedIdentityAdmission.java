package io.memoryos.iam;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;

/** Browser admission after {@link SignInAdmission} has verified the trusted issuer and identity-provider claim. */
public interface TrustedIdentityAdmission {

    ActorId admit(TenantId tenantId, ExternalIdentity identity);
}
