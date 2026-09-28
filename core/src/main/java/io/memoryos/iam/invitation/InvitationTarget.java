package io.memoryos.iam.invitation;
import io.memoryos.shared.TenantId;

public record InvitationTarget(
        TenantId tenantId,
        String tenantDisplayName
) {
}
