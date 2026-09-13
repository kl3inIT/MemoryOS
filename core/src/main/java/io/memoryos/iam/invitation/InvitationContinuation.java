package io.memoryos.iam.invitation;

import java.time.Instant;
import java.util.UUID;
import io.memoryos.iam.tenant.TenantId;

public record InvitationContinuation(
        UUID invitationId,
        TenantId tenantId,
        String tenantDisplayName,
        Instant expiresAt
) {
}
