package io.memoryos.ingestion;

import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceOperationTraceContext;
import io.memoryos.iam.TenantId;

import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record OperationDelivery(
        TenantId tenantId,
        OperationWorkload workload,
        SourceOperationId operationId,
        UUID deliveryId,
        @Nullable SourceOperationTraceContext origin
) {
    public OperationDelivery(TenantId tenantId, OperationWorkload workload, SourceOperationId operationId, UUID deliveryId) {
        this(tenantId, workload, operationId, deliveryId, null);
    }

    public OperationDelivery {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workload, "workload must not be null");
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
    }
}
