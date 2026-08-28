package io.memoryos.api.source.contract;

import io.memoryos.connector.SourceUploadResult;

public record SourceUploadResponse(SourceItemResponse item, SourceOperationResponse operation) {
    public static SourceUploadResponse from(SourceUploadResult upload) {
        return new SourceUploadResponse(
                SourceItemResponse.from(upload.item()),
                SourceOperationResponse.from(upload.operation())
        );
    }
}
