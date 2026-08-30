package io.memoryos.tenant;

import io.memoryos.identity.ActorId;

import java.util.Optional;

public interface TenantMembershipProvisioner {

    Optional<InvitationAuthority> findInvitationAuthority(ActorId actorId);

    Optional<InvitationTarget> findActiveInvitationTarget(TenantId tenantId);

    boolean hasAnyMembership(ActorId actorId);

    void grantMember(TenantId tenantId, ActorId actorId);

}
