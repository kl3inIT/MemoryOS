package io.memoryos.ingestion;

import io.memoryos.connector.SourceOperationId;
import io.memoryos.tenant.TenantId;

import java.util.Objects;
import java.util.UUID;

public record OperationDelivery(
        TenantId tenantId,
        OperationWorkload workload,
        SourceOperationId operationId,
        UUID deliveryId
) {
    public OperationDelivery {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(workload, "workload must not be null");
        Objects.requireNonNull(operationId, "operationId must not be null");
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
    }
}
