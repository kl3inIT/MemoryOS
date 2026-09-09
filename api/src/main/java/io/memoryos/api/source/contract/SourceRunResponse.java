package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceRun;
import java.time.Instant;
import io.memoryos.connector.SourceRunIndexingStatus;
import io.memoryos.connector.SourceRunStatus;
import io.memoryos.connector.SourceRunTrigger;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "SourceRun", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceRunResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID sourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable SourceRunTrigger trigger,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable UUID actorId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SourceRunStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SourceRunStatus acquisitionStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SourceRunIndexingStatus indexingStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Instant startedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Instant acquisitionCompletedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Instant completedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long scopeRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long credentialRevision,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Instant nextRetryAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable String errorCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean detailsExpired,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SourceRunCountsResponse counts
) {
    public static SourceRunResponse from(SourceRun value) {
        return new SourceRunResponse(
                value.id(),
                value.sourceId().value(),
                value.trigger(),
                value.actorId(),
                value.status(),
                value.acquisitionStatus(),
                value.indexingStatus(),
                value.createdAt(),
                value.startedAt(),
                value.acquisitionCompletedAt(),
                value.completedAt(),
                value.scopeRevision(),
                value.credentialRevision(),
                value.nextRetryAt(),
                value.errorCode(),
                value.detailsExpired(),
                SourceRunCountsResponse.from(value.counts())
        );
    }
}
