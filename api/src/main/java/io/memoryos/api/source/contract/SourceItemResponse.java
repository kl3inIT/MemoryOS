package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceItemView;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record SourceItemResponse(
        UUID id,
        String filename,
        String sha256,
        long sizeBytes,
        String status,
        Instant uploadedAt,
        @Nullable UUID latestOperationId,
        @Nullable String errorCode
) {
    public static SourceItemResponse from(SourceItemView item) {
        return new SourceItemResponse(
                item.id().value(),
                item.filename(),
                item.sha256(),
                item.sizeBytes(),
                item.status().name(),
                item.uploadedAt(),
                item.latestOperationId() == null ? null : item.latestOperationId().value(),
                item.errorCode()
        );
    }
}
