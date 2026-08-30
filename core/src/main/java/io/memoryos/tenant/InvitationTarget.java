package io.memoryos.tenant;

public record InvitationTarget(
        TenantId tenantId,
        String tenantDisplayName
) {
}
