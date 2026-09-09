package io.memoryos.iam;

/** Browser admission after the composition root has verified the trusted identity-provider claim. */
public interface TrustedIdentityAdmission {

    ActorId admit(TenantId tenantId, ExternalIdentity identity);
}
