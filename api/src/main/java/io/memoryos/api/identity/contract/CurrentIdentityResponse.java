package io.memoryos.api.identity.contract;

import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantMembership;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
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
                description = "Expanded global capabilities backed by current server enforcement.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        List<IamCapability> capabilities,
        @Schema(
                description = "Eligible capabilities available only within resources managed by this actor.",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        List<IamCapability> scopedCapabilities,
        @Schema(
                description = "Monotonic Tenant IAM revision used only to invalidate private client data.",
                minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        long authorizationVersion
) {

    public static CurrentIdentityResponse from(
            UUID actorId,
            @Nullable TenantMembership membership,
            Set<IamCapability> capabilities,
            Set<IamCapability> scopedCapabilities,
            long authorizationVersion
    ) {
        return new CurrentIdentityResponse(
                actorId,
                membership == null ? null : CurrentTenantResponse.from(membership),
                sorted(capabilities),
                sorted(scopedCapabilities),
                membership == null ? 0 : authorizationVersion
        );
    }

    private static List<IamCapability> sorted(Set<IamCapability> capabilities) {
        return capabilities.stream()
                .sorted(Comparator.comparingInt(IamCapability::ordinal))
                .toList();
    }
}
