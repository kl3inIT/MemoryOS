package io.memoryos.connector.sync;

import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceSelectionProcessor;
import io.memoryos.connector.sync.persistence.JdbcSourceSelectionRepository;
import io.memoryos.shared.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Hands a delivered selection request to the adapter of the provider it was accepted for. */
@Service
public class DefaultSourceSelectionProcessor implements SourceSelectionProcessor {
    private final JdbcSourceSelectionRepository selections;
    private final SourceSelectionAdapterRegistry adapters;

    public DefaultSourceSelectionProcessor(JdbcSourceSelectionRepository selections,
            SourceSelectionAdapterRegistry adapters) {
        this.selections = selections;
        this.adapters = adapters;
    }

    @Override
    public Optional<Work> claim(TenantId tenant, SourceOperationId operation, UUID deliveryId) {
        return selections.sourceType(tenant, operation)
                .flatMap(type -> adapters.require(type).claim(tenant, operation, deliveryId));
    }

    @Override
    public boolean renew(Work work) {
        return adapters.require(work.sourceType()).renew(work);
    }

    @Override
    public Result execute(Work work) {
        return adapters.require(work.sourceType()).execute(work);
    }
}
