package io.memoryos.iam.invitation;

import java.time.Instant;
import java.util.UUID;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;

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
