package io.memoryos.iam;

import java.time.Instant;
import java.util.UUID;

public record InvitationView(
        UUID id,
        TenantId tenantId,
        String email,
        InvitationStatus status,
        Instant createdAt,
        Instant expiresAt,
        ActorId acceptedActorId,
        Instant acceptedAt,
        Instant revokedAt
) {
}
