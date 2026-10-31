package io.memoryos.connector.sync;

import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceSelectionProcessor.Result;
import io.memoryos.connector.SourceSelectionProcessor.Work;
import io.memoryos.connector.SourceType;
import io.memoryos.shared.TenantId;
import java.util.Optional;
import java.util.UUID;

/**
 * How one provider verifies an accepted selection and activates it. One adapter per external
 * {@link SourceType}, held by the {@link SourceSelectionAdapterRegistry}.
 */
public interface SourceSelectionAdapter {
    SourceType type();

    Optional<Work> claim(TenantId tenant, SourceOperationId operation, UUID deliveryId);

    boolean renew(Work work);

    Result execute(Work work);
}
