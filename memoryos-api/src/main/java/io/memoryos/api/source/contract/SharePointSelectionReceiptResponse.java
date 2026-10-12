package io.memoryos.api.source.contract;

import io.memoryos.connector.SharePointSourceService.SelectionReceipt;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "SharePointSelectionReceiptResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record SharePointSelectionReceiptResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SourceOperationResponse operation) {

    public static SharePointSelectionReceiptResponse from(SelectionReceipt value) {
        return new SharePointSelectionReceiptResponse(value.sourceId().value(),
                SourceOperationResponse.from(value.operation()));
    }
}
