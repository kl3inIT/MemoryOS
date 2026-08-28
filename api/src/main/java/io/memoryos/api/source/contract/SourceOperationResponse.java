package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceOperationView;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record SourceOperationResponse(
        UUID id,
        String type,
        String status,
        Instant createdAt,
        @Nullable Instant completedAt,
        @Nullable String errorCode
) {
    public static SourceOperationResponse from(SourceOperationView operation) {
        return new SourceOperationResponse(
                operation.id().value(),
                operation.type().name(),
                operation.status().name(),
                operation.createdAt(),
                operation.completedAt(),
                operation.errorCode()
        );
    }
}
