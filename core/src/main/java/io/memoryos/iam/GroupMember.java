package io.memoryos.iam;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

public record GroupMember(
        ActorId actorId,
        @Nullable String displayName,
        @Nullable String email,
        AccountType accountType,
        TenantMembershipStatus status,
        boolean manager,
        boolean protectedOwner
) {
    public GroupMember {
        Objects.requireNonNull(actorId, "actorId must not be null");
        Objects.requireNonNull(accountType, "accountType must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
