package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceCounts;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        name = "SourceCounts",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        description = "Every source the actor may read, whatever the search and filters of the page."
)
public record SourceCountsResponse(
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long total,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long notStarted,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long indexing,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long active,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long failed,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long paused,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long pausing,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        long deleting,
        @Schema(minimum = "0", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Documents across those sources.")
        long documents,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether any of those sources has pending work.")
        boolean pendingWork
) {

    public static SourceCountsResponse from(SourceCounts counts) {
        return new SourceCountsResponse(counts.total(), counts.notStarted(), counts.indexing(), counts.active(),
                counts.failed(), counts.paused(), counts.pausing(), counts.deleting(), counts.documents(),
                counts.pendingWork());
    }
}
