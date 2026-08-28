package io.memoryos.organization;

import io.memoryos.identity.ActorId;

import java.util.Optional;

public interface OrganizationAccessResolver {

    boolean hasActiveOrganization(ActorId actorId);

    default Optional<OrganizationId> findActiveOrganization(ActorId actorId) {
        return findSessionAuthority(actorId).map(OrganizationSessionAuthority::organizationId);
    }

    default Optional<OrganizationId> findActiveOwnerOrganization(ActorId actorId) {
        return findSessionAuthority(actorId)
                .filter(authority -> authority.role() == OrganizationMembershipRole.OWNER)
                .map(OrganizationSessionAuthority::organizationId);
    }


    Optional<OrganizationSessionAuthority> findSessionAuthority(ActorId actorId);
}
