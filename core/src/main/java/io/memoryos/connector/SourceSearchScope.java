package io.memoryos.connector;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Authorization snapshot for one Search call. The repository rechecks eligibility after index IO. */
public record SourceSearchScope(TenantId tenant, ActorId actor, Map<UUID, SourceType> sources) {
    public SourceSearchScope {
        java.util.Objects.requireNonNull(tenant, "tenant");
        java.util.Objects.requireNonNull(actor, "actor");
        sources = Map.copyOf(sources);
    }
    public Set<SourceType> types() { return Set.copyOf(sources.values()); }
}
