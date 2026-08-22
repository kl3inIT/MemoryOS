package io.memoryos.invitation;

import io.memoryos.organization.OrganizationId;

import java.time.Instant;
import java.util.UUID;

public record InvitationContinuation(
        UUID invitationId,
        OrganizationId organizationId,
        String organizationDisplayName,
        Instant expiresAt
) {
}
