package io.memoryos.connector;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record SourceRun(
        UUID id, SourceId sourceId, @Nullable SourceRunTrigger trigger, @Nullable UUID actorId,
        SourceRunStatus status, SourceRunStatus acquisitionStatus, SourceRunIndexingStatus indexingStatus,
        Instant createdAt, @Nullable Instant startedAt, @Nullable Instant acquisitionCompletedAt,
        @Nullable Instant completedAt, long scopeRevision, long credentialRevision,
        @Nullable Instant nextRetryAt, @Nullable String errorCode, boolean detailsExpired,
        // REFRESH or PRUNE for SharePoint; null where a connector has one kind of run.
        @Nullable String runKind, SourceRunCounts counts
) {}
