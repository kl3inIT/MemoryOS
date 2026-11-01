package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceUploadReceipt;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "SourceUploadReceipt", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SourceUploadReceiptResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SourceItemResponse item,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SourceOperationResponse operation
) {
    public static SourceUploadReceiptResponse from(SourceUploadReceipt upload) {
        return new SourceUploadReceiptResponse(
                SourceItemResponse.from(upload.item()),
                SourceOperationResponse.from(upload.operation())
        );
    }
}
