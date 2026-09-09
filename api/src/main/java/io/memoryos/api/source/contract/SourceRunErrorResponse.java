package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceRunError;
import java.time.Instant;
import io.memoryos.connector.SourceRunErrorStage;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "SourceRunError", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceRunErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID runId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable UUID operationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable UUID itemId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable String fileId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable String fileName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SourceRunErrorStage stage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant occurredAt
) {
    public static SourceRunErrorResponse from(SourceRunError value) {
        return new SourceRunErrorResponse(
                value.id(),
                value.runId(),
                value.operationId(),
                value.itemId(),
                value.fileId(),
                value.fileName(),
                value.stage(),
                value.code(),
                value.occurredAt()
        );
    }
}
