package io.memoryos.invitation;

import io.memoryos.identity.ExternalIdentity;
import io.memoryos.tenant.TenantId;

import java.util.UUID;

public record InvitationAcceptance(
        UUID invitationId,
        TenantId tenantId,
        ExternalIdentity externalIdentity,
        String email,
        boolean emailVerified
) {
}
