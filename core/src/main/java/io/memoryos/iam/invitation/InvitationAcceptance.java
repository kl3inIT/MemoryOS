package io.memoryos.iam.invitation;

import java.util.UUID;
import io.memoryos.iam.identity.ExternalIdentity;
import io.memoryos.iam.tenant.TenantId;

public record InvitationAcceptance(
        UUID invitationId,
        TenantId tenantId,
        ExternalIdentity externalIdentity,
        String email,
        boolean emailVerified
) {
}
