package io.memoryos.invitation;

import io.memoryos.identity.ExternalIdentity;
import io.memoryos.organization.OrganizationId;

import java.util.UUID;

public record InvitationAcceptance(
        UUID invitationId,
        OrganizationId organizationId,
        ExternalIdentity externalIdentity,
        String email,
        boolean emailVerified
) {
}
