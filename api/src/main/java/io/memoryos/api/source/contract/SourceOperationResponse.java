package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceOperationView;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SourceOperation", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceOperationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Instant completedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
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
