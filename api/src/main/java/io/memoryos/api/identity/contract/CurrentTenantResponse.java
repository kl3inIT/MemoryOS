package io.memoryos.api.identity.contract;

import io.memoryos.iam.TenantMembershipRole;
import io.memoryos.iam.TenantMembership;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CurrentTenant", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CurrentTenantResponse(
        @Schema(
                description = "Display name of the actor's active Tenant.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String displayName,
        @Schema(
                description = "Stable Tenant membership role used for presentation.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        TenantMembershipRole role
) {

    public static CurrentTenantResponse from(TenantMembership membership) {
        return new CurrentTenantResponse(
                membership.tenantDisplayName(),
                membership.role()
        );
    }
}
