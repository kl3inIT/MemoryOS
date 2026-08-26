package io.memoryos.api.identity.contract;

import io.memoryos.organization.OrganizationMembershipRole;
import io.memoryos.organization.OrganizationSessionAuthority;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CurrentOrganization", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CurrentOrganizationResponse(
        @Schema(
                description = "Display name of the actor's active Organization.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String displayName,
        @Schema(
                description = "Stable Organization membership role used for presentation.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        OrganizationMembershipRole role
) {

    public static CurrentOrganizationResponse from(OrganizationSessionAuthority authority) {
        return new CurrentOrganizationResponse(
                authority.organizationDisplayName(),
                authority.role()
        );
    }
}
