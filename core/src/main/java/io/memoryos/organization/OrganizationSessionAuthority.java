package io.memoryos.organization;

import java.util.Objects;

public record OrganizationSessionAuthority(
        OrganizationId organizationId,
        String organizationDisplayName,
        OrganizationMembershipRole role
) {

    public OrganizationSessionAuthority {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(organizationDisplayName, "organizationDisplayName must not be null");
        if (organizationDisplayName.isBlank()) {
            throw new IllegalArgumentException("organizationDisplayName must not be blank");
        }
        Objects.requireNonNull(role, "role must not be null");
    }

}
