package io.memoryos.connector.application;

import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceRun;
import io.memoryos.connector.SourceRunHistoryService;
import io.memoryos.connector.persistence.JdbcSourceRunHistoryRepository;
import io.memoryos.connector.persistence.JdbcSourceQueryRepository;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantId;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class DefaultSourceRunHistoryService implements SourceRunHistoryService {
    private final JdbcSourceRunHistoryRepository history;
    private final IamAuthorization authorization;
    private final JdbcSourceQueryRepository sources;

    public DefaultSourceRunHistoryService(JdbcSourceRunHistoryRepository history, IamAuthorization authorization,
            JdbcSourceQueryRepository sources) {
        this.history = history;
        this.authorization = authorization;
        this.sources = sources;
    }

    @Override
    public Page list(ActorId actor, SourceId source, Query query) {
        TenantId tenant = readableTenant(actor, source);
        requireSize(query.size());
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to()))
            throw SourceException.invalid("The history date range is invalid.", "history from must precede to");
        return history.list(tenant, source, query);
    }

    @Override
    public SourceRun get(ActorId actor, SourceId source, UUID runId) {
        return history.get(readableTenant(actor, source), source, Objects.requireNonNull(runId));
    }

    @Override
    public ErrorPage errors(ActorId actor, SourceId source, UUID runId, @Nullable String cursor, int size) {
        TenantId tenant = readableTenant(actor, source);
        requireSize(size);
        history.get(tenant, source, Objects.requireNonNull(runId));
        return history.errors(tenant, source, runId, cursor, size);
    }

    private TenantId readableTenant(ActorId actor, SourceId source) {
        var access = authorization.require(Objects.requireNonNull(actor), IamCapability.SOURCES_READ, true);
        var capabilities = authorization.effectiveCapabilities(actor);
        sources.summary(access.tenantId(), actor, Objects.requireNonNull(source),
                capabilities.contains(IamCapability.SOURCES_READ),
                capabilities.contains(IamCapability.SOURCES_MANAGE),
                capabilities.contains(IamCapability.SOURCES_DELETE));
        return access.tenantId();
    }

    private static void requireSize(int size) {
        if (size < 1 || size > 100)
            throw SourceException.invalid("History page size must be between 1 and 100.", "invalid history page size");
    }
}
