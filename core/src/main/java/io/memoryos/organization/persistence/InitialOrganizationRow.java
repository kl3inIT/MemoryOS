package io.memoryos.organization.persistence;

import java.util.UUID;

public record InitialOrganizationRow(
        UUID organizationId,
        String organizationSlug,
        String organizationDisplayName,
        String organizationStatus,
        UUID defaultWorkspaceId,
        String bootstrapReference,
        String workspaceSlug,
        String workspaceDisplayName,
        String workspaceStatus,
        UUID ownerActorId
) {
}
