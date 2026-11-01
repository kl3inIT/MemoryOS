package io.memoryos.iam;

import java.util.UUID;
import io.memoryos.shared.TenantId;

public record InvitationAcceptance(
        UUID invitationId,
        TenantId tenantId,
        ExternalIdentity externalIdentity,
        String email,
        boolean emailVerified
) {
}
