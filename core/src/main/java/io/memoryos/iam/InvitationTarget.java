package io.memoryos.iam;

public record InvitationTarget(
        TenantId tenantId,
        String tenantDisplayName
) {
}
