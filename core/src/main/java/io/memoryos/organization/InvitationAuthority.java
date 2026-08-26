package io.memoryos.organization;

public record InvitationAuthority(
        OrganizationId organizationId,
        String organizationDisplayName
) {
}
