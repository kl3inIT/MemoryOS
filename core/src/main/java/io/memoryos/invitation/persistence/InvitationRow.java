package io.memoryos.invitation.persistence;

import io.memoryos.invitation.InvitationStatus;

import java.time.Instant;
import java.util.UUID;

public record InvitationRow(
        UUID id,
        UUID tenantId,
        String email,
        InvitationStatus status,
        Instant createdAt,
        Instant expiresAt,
        UUID acceptedActorId,
        Instant acceptedAt,
        Instant revokedAt
) {
}
