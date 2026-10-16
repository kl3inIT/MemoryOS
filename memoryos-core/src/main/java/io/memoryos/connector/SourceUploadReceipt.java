package io.memoryos.connector;

import java.util.Objects;

public record SourceUploadReceipt(SourceItemView item, SourceOperationView operation) {
    public SourceUploadReceipt {
        Objects.requireNonNull(item, "item must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
    }
}
