package io.memoryos.organization;

import io.memoryos.identity.ActorId;

import java.util.Optional;

public interface OrganizationMembershipProvisioner {

    Optional<InvitationAuthority> findInvitationAuthority(ActorId actorId);

    Optional<InvitationTarget> findActiveInvitationTarget(OrganizationId organizationId);

    boolean hasAnyMembership(ActorId actorId);

    void grantMember(OrganizationId organizationId, ActorId actorId);

}
