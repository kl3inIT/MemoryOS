package io.memoryos.api.groups.contract;

import io.memoryos.iam.AccountType;
import io.memoryos.iam.GroupMember;
import io.memoryos.iam.TenantMembershipStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

@Schema(name = "GroupMember", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record GroupMemberResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID actorId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        AccountType accountType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        TenantMembershipStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean isManager,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean protectedOwner
) {
    public static GroupMemberResponse from(GroupMember member) {
        return new GroupMemberResponse(
                member.actorId().value(),
                member.displayName(),
                member.email(),
                member.accountType(),
                member.status(),
                member.manager(),
                member.protectedOwner()
        );
    }
}
