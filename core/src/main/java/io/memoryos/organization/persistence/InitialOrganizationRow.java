package io.memoryos.organization.persistence;

import java.util.UUID;

public record InitialOrganizationRow(
        UUID organizationId,
        String organizationSlug,
        String organizationDisplayName,
        String organizationStatus,
        String bootstrapReference,
        UUID ownerActorId
) {
}
