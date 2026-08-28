package io.memoryos.connector;

import java.time.Instant;

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
        @Nullable String errorCode
) {
}
