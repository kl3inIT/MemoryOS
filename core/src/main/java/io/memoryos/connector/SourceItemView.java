package io.memoryos.connector;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

public record SourceItemView(
        SourceItemId id,
        String filename,
        String sha256,
        long sizeBytes,
        SourceItemStatus status,
        Instant uploadedAt,
        @Nullable Instant lastIndexedAt,
        @Nullable SourceIndexAttemptView latestAttempt,
        @Nullable String errorCode
) {
}
