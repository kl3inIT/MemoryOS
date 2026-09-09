package io.memoryos.connector.application;

import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceRun;
import io.memoryos.connector.SourceRunHistoryService;
import io.memoryos.connector.persistence.JdbcSourceRunHistoryRepository;
import io.memoryos.identity.ActorId;
import io.memoryos.tenant.TenantAccessResolver;
import io.memoryos.tenant.TenantId;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultSourceRunHistoryService implements SourceRunHistoryService {
    private final JdbcSourceRunHistoryRepository history;
    private final TenantAccessResolver tenants;

    public DefaultSourceRunHistoryService(JdbcSourceRunHistoryRepository history, TenantAccessResolver tenants) {
        this.history = history;
        this.tenants = tenants;
    }

    @Override
    public Page list(ActorId actor, SourceId source, Query query) {
        TenantId tenant = owner(actor, source);
        requireSize(query.size());
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to()))
            throw SourceException.invalid("The history date range is invalid.", "history from must precede to");
        return history.list(tenant, source, query);
    }

    @Override
    public SourceRun get(ActorId actor, SourceId source, UUID runId) {
        return history.get(owner(actor, source), source, Objects.requireNonNull(runId));
    }

    @Override
    public ErrorPage errors(ActorId actor, SourceId source, UUID runId, @Nullable String cursor, int size) {
        TenantId tenant = owner(actor, source);
        requireSize(size);
        history.get(tenant, source, Objects.requireNonNull(runId));
        return history.errors(tenant, source, runId, cursor, size);
    }

    private TenantId owner(ActorId actor, SourceId source) {
        TenantId tenant = tenants.findActiveOwnerTenant(Objects.requireNonNull(actor)).orElseThrow(SourceException::notOwner);
        history.requireSource(tenant, Objects.requireNonNull(source));
        return tenant;
    }

    private static void requireSize(int size) {
        if (size < 1 || size > 100)
            throw SourceException.invalid("History page size must be between 1 and 100.", "invalid history page size");
    }
}
