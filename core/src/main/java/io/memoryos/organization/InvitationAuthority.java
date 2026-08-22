package io.memoryos.organization;

public record InvitationAuthority(
        OrganizationId organizationId,
        WorkspaceId defaultWorkspaceId,
        String organizationDisplayName
) {
}
