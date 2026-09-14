package io.memoryos.iam.invitation;
import io.memoryos.iam.tenant.TenantId;

public record InvitationTarget(
        TenantId tenantId,
        String tenantDisplayName
) {
}
