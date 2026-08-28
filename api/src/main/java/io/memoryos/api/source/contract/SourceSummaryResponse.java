package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceSummary;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record SourceSummaryResponse(
        UUID id,
        String name,
        String type,
        String access,
        String status,
        boolean pendingWork,
        long documentCount,
        @Nullable Instant lastSucceededAt,
        @Nullable String errorCode
) {
    public static SourceSummaryResponse from(SourceSummary source) {
        return new SourceSummaryResponse(
                source.id().value(),
                source.name(),
                source.type().name(),
                source.access().name(),
                source.status().name(),
                source.pendingWork(),
                source.documentCount(),
                source.lastSucceededAt(),
                source.errorCode()
        );
    }
}
