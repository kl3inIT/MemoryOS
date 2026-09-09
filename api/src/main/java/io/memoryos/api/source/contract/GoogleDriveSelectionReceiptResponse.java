package io.memoryos.api.source.contract;

import io.memoryos.connector.GoogleDriveSourceService.SelectionReceipt;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name="GoogleDriveSelectionReceiptResponse",additionalProperties=Schema.AdditionalPropertiesValue.FALSE)
public record GoogleDriveSelectionReceiptResponse(
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) UUID sourceId,
        @Schema(requiredMode=Schema.RequiredMode.REQUIRED) SourceOperationResponse operation) {
    public static GoogleDriveSelectionReceiptResponse from(SelectionReceipt value) {
        return new GoogleDriveSelectionReceiptResponse(value.sourceId().value(),SourceOperationResponse.from(value.operation()));
    }
}
