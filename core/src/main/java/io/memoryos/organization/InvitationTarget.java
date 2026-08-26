package io.memoryos.organization;

public record InvitationTarget(
        OrganizationId organizationId,
        String organizationDisplayName
) {
}
