package io.memoryos.organization;

import io.memoryos.identity.ActorId;

@FunctionalInterface
public interface OrganizationAccessResolver {

    boolean hasActiveOrganization(ActorId actorId);
}
