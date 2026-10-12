package io.memoryos.api.source.contract;

import io.memoryos.connector.LarkSourceService.SelectionReceipt;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "LarkSelectionReceiptResponse", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LarkSelectionReceiptResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SourceOperationResponse operation) {

    public static LarkSelectionReceiptResponse from(SelectionReceipt value) {
        return new LarkSelectionReceiptResponse(value.sourceId().value(), SourceOperationResponse.from(value.operation()));
    }
}
