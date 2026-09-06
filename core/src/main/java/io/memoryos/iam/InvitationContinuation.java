package io.memoryos.iam;

import java.time.Instant;
import java.util.UUID;

public record InvitationContinuation(
        UUID invitationId,
        TenantId tenantId,
        String tenantDisplayName,
        Instant expiresAt
) {
}
