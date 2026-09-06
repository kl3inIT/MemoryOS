package io.memoryos.invitation;

import io.memoryos.tenant.TenantId;

import java.time.Instant;
import java.util.UUID;

public record InvitationContinuation(
        UUID invitationId,
        TenantId tenantId,
        String tenantDisplayName,
        Instant expiresAt
) {
}
