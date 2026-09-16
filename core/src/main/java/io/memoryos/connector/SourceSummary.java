package io.memoryos.connector;

import io.memoryos.iam.identity.ActorId;

import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

public record SourceSummary(
        SourceId id,
        String name,
        SourceType type,
        SourceAccess access,
        SourceStatus status,
        boolean pendingWork,
        long documentCount,
        @Nullable Instant lastSucceededAt,
        @Nullable String errorCode,
        /** The Actor allowed to attach this Source to the Groups they manage; null leaves that to global authority. */
        @Nullable ActorId managerActorId,
        /** That Actor's profile name, so naming the manager needs no separate directory read. */
        @Nullable String managerName,
        SourcePermissions permissions
) {
    public SourceSummary {
        Objects.requireNonNull(permissions, "permissions must not be null");
    }
}
