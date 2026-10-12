package io.memoryos.api.users.contract;

import io.memoryos.iam.AccountType;
import io.memoryos.iam.UserListItem;
import io.memoryos.iam.UserStatus;
import io.memoryos.iam.TenantMembershipRole;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

@Schema(name = "UserListItem", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UserListItemResponse(
        @Schema(
                description = "Stable Actor identifier for a membership row; null for an invitation row.",
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = true
        )
        @Nullable UUID actorId,
        @Schema(
                description = "Invitation identifier for an invited row; null for a membership row.",
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = true
        )
        @Nullable UUID invitationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Boolean emailVerified,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable String profileIssuer,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, enumAsRef = true)
        @Nullable TenantMembershipRole role,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, enumAsRef = true)
        @Nullable AccountType accountType,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UserStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<UserGroupResponse> groups,
        @Schema(
                description = "Invitation expiry for an invited row; null for a membership row.",
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = true
        )
        @Nullable Instant invitationExpiresAt
) {

    public static UserListItemResponse from(UserListItem entry) {
        return new UserListItemResponse(
                entry.actorId() == null ? null : entry.actorId().value(),
                entry.invitationId(),
                entry.displayName(),
                entry.email(),
                entry.emailVerified(),
                entry.profileIssuer(),
                entry.role(),
                entry.accountType(),
                entry.status(),
                entry.groups().stream().map(UserGroupResponse::from).toList(),
                entry.invitationExpiresAt()
        );
    }
}
