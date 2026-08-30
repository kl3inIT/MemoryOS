package io.memoryos.tenant;

public record InvitationAuthority(
        TenantId tenantId,
        String tenantDisplayName
) {
}
