package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceAction;
import io.memoryos.connector.SourceSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SourceSummary", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceSummaryResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String access,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean pendingWork,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        long documentCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Instant lastSucceededAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable String errorCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> actions
) {
    public SourceSummaryResponse {
        actions = List.copyOf(actions);
    }

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
                source.errorCode(),
                source.actions().stream()
                        .map(SourceAction::token)
                        .toList()
        );
    }
}
