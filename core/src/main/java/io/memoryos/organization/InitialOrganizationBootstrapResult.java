package io.memoryos.organization;

import io.memoryos.identity.ActorId;

import java.util.Objects;

public record InitialOrganizationBootstrapResult(
        ActorId ownerActorId,
        OrganizationId organizationId,
        WorkspaceId defaultWorkspaceId,
        boolean created
) {

    public InitialOrganizationBootstrapResult {
        Objects.requireNonNull(ownerActorId, "ownerActorId must not be null");
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(defaultWorkspaceId, "defaultWorkspaceId must not be null");
    }
}
