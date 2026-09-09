package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceItemView;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SourceItem", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceItemResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String filename,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String sha256,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long sizeBytes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant uploadedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "Most recent retained successful indexing completion for the current file version. Null means no retained success is known, not necessarily never indexed.")
        @Nullable Instant lastIndexedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable SourceIndexAttemptResponse latestAttempt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
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
                item.lastIndexedAt(),
                item.latestAttempt() == null ? null : SourceIndexAttemptResponse.from(item.latestAttempt()),
                item.errorCode()
        );
    }
}
