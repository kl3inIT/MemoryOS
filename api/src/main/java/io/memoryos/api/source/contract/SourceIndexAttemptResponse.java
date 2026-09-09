package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceIndexAttemptView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "SourceIndexAttempt", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceIndexAttemptResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String filename,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "Actual first processing start; null when not started or unavailable in retained history.")
        @Nullable Instant startedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable Instant completedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable String errorCode
) {
    public static SourceIndexAttemptResponse from(SourceIndexAttemptView attempt) {
        return new SourceIndexAttemptResponse(attempt.id().value(), attempt.filename(), attempt.status().name(),
                attempt.createdAt(), attempt.startedAt(), attempt.completedAt(), attempt.errorCode());
    }
}
