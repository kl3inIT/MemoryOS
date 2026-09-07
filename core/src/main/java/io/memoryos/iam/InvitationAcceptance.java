package io.memoryos.iam;

import java.util.UUID;

public record InvitationAcceptance(
        UUID invitationId,
        TenantId tenantId,
        ExternalIdentity externalIdentity,
        String email,
        boolean emailVerified
) {
}
