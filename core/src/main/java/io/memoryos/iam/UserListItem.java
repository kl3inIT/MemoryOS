package io.memoryos.iam;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record UserListItem(
        @Nullable ActorId actorId,
        @Nullable UUID invitationId,
        @Nullable String displayName,
        @Nullable String email,
        @Nullable Boolean emailVerified,
        @Nullable String profileIssuer,
        @Nullable TenantMembershipRole role,
        @Nullable AccountType accountType,
        UserStatus status,
        List<GroupIdentity> groups,
        @Nullable Instant invitationExpiresAt
) {

    public UserListItem {
        Objects.requireNonNull(status, "status must not be null");
        groups = List.copyOf(Objects.requireNonNull(groups, "groups must not be null"));
        if ((actorId == null) == (invitationId == null)) {
            throw new IllegalArgumentException("exactly one user list item identifier must be present");
        }
        if (actorId != null) {
            Objects.requireNonNull(role, "membership role must not be null");
            Objects.requireNonNull(accountType, "membership accountType must not be null");
            if (status == UserStatus.INVITED || invitationExpiresAt != null) {
                throw new IllegalArgumentException("membership user has invitation state");
            }
            if ((profileIssuer == null) != (emailVerified == null)) {
                throw new IllegalArgumentException("membership profile provenance and verification must agree");
            }
        } else if (displayName != null
                || email == null
                || email.isBlank()
                || role != null
                || accountType != null
                || status != UserStatus.INVITED
                || invitationExpiresAt == null
                || emailVerified != null
                || profileIssuer != null
                || !groups.isEmpty()) {
            throw new IllegalArgumentException("invited user has membership profile state");
        }
    }
}
