package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceRunCounts;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "SourceRunCounts", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceRunCountsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Long scanned,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Long acquired,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Long published,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Long unchanged,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Long alreadyPending,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Long acquisitionFailed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Long indexingFailed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Long skipped,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Long removed,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Long indexingPending,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Long indexingSuperseded,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @Nullable Long indexingCancelled
) {
    public static SourceRunCountsResponse from(SourceRunCounts value) {
        return new SourceRunCountsResponse(
                value.scanned(),
                value.acquired(),
                value.published(),
                value.unchanged(),
                value.alreadyPending(),
                value.acquisitionFailed(),
                value.indexingFailed(),
                value.skipped(),
                value.removed(),
                value.indexingPending(),
                value.indexingSuperseded(),
                value.indexingCancelled()
        );
    }
}
