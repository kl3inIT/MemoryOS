package io.memoryos.iam;

import java.util.Optional;

public interface TenantMembershipProvisioner {

    Optional<InvitationTarget> findActiveInvitationTarget(TenantId tenantId);

    boolean hasAnyMembership(ActorId actorId);

    void grantMember(TenantId tenantId, ActorId actorId);
}
