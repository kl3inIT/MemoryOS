package io.memoryos.invitation;

import io.memoryos.identity.ActorId;
import io.memoryos.organization.OrganizationId;

import java.time.Instant;
import java.util.UUID;

public record InvitationView(
        UUID id,
        OrganizationId organizationId,
        String email,
        InvitationStatus status,
        Instant createdAt,
        Instant expiresAt,
        ActorId acceptedActorId,
        Instant acceptedAt,
        Instant revokedAt
) {
}
