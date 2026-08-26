package io.memoryos.organization;

import io.memoryos.identity.ActorId;

import java.util.Optional;

public interface OrganizationAccessResolver {

    boolean hasActiveOrganization(ActorId actorId);

    Optional<OrganizationSessionAuthority> findSessionAuthority(ActorId actorId);
}
