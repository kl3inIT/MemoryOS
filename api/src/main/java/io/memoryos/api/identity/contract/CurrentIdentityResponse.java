package io.memoryos.api.identity.contract;

import io.memoryos.tenant.TenantMembershipRole;
import io.memoryos.tenant.TenantMembership;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

@Schema(name = "CurrentIdentity", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CurrentIdentityResponse(
        @Schema(
                description = "Stable internal MemoryOS actor identifier.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        UUID actorId,
        @Schema(
                description = "Active Tenant context, or null when the actor has no active Tenant membership.",
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = true
        )
        @Nullable CurrentTenantResponse tenant,
        @Schema(
                description = "Canonical capabilities backed by current server enforcement.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        List<CurrentIdentityCapability> capabilities
) {

    public static CurrentIdentityResponse from(
            UUID actorId,
            @Nullable TenantMembership membership
    ) {
        return new CurrentIdentityResponse(
                actorId,
                membership == null ? null : CurrentTenantResponse.from(membership),
                membership != null && membership.role() == TenantMembershipRole.OWNER
                        ? List.of(
                        CurrentIdentityCapability.INVITATIONS_MANAGE,
                        CurrentIdentityCapability.SOURCES_MANAGE
                )
                        : List.of()
        );
    }
}
