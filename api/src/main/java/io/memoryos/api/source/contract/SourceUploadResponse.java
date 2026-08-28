package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceUploadResult;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SourceUploadResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceUploadResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SourceItemResponse item,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        SourceOperationResponse operation
) {
    public static SourceUploadResponse from(SourceUploadResult upload) {
        return new SourceUploadResponse(
                SourceItemResponse.from(upload.item()),
                SourceOperationResponse.from(upload.operation())
        );
    }
}
