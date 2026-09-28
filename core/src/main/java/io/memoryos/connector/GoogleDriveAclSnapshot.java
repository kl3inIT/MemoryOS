package io.memoryos.connector;

import io.memoryos.document.DocumentId;
import io.memoryos.shared.TenantId;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Provider permission observations, not effective reader authorization. Google permission identities
 * are not MemoryOS Actors; groups are not expanded. Consumers must assess status and timestamps even
 * when the retained successful observation has CURRENT lifecycle context.
 */
public record GoogleDriveAclSnapshot(
        TenantId tenantId,
        SourceId sourceId,
        String fileId,
        long revision,
        List<GoogleDriveProvider.Permission> permissions,
        Status status,
        Observation lastAttempt,
        @Nullable Observation lastSuccess,
        @Nullable String errorCode,
        @Nullable String errorMessage,
        ContextStatus contextStatus,
        CurrentContext currentContext,
        @Nullable SourceItemId sourceItemId,
        List<DocumentId> documentIds,
        Instant readAt
) {
    public GoogleDriveAclSnapshot {
        permissions = List.copyOf(permissions);
        documentIds = List.copyOf(documentIds);
    }

    @Override public @NonNull String toString() { return "GoogleDriveAclSnapshot[redacted]"; }

    public enum Status { SUCCEEDED, FAILED }

    /** CURRENT is context matching, not an age limit or an access grant. */
    public enum ContextStatus { CURRENT, STALE, INVALID, UNOBSERVED }

    public record Observation(Instant at, SourceOperationId operationId, CredentialId credentialId,
                              long credentialRevision, long scopeRevision, long generation) {}

    public record CurrentContext(boolean tenantActive, boolean sourceActive, CredentialId credentialId,
                                 long credentialRevision, boolean credentialActive, long scopeRevision,
                                 long generation, @Nullable Long membershipGeneration, boolean selected,
                                 boolean itemRemoved) {}
}
