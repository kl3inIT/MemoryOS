package io.memoryos.organization;

public record InvitationTarget(
        OrganizationId organizationId,
        WorkspaceId defaultWorkspaceId,
        String organizationDisplayName
) {
}
