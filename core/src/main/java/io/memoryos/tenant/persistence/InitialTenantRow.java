package io.memoryos.tenant.persistence;

import java.util.UUID;

public record InitialTenantRow(
        UUID tenantId,
        String tenantSlug,
        String tenantDisplayName,
        String tenantStatus,
        String bootstrapReference,
        UUID ownerActorId
) {
}
