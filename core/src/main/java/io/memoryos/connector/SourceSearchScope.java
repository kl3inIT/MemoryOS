package io.memoryos.connector;

import io.memoryos.iam.TenantId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Authorization snapshot for one Search call. The repository rechecks eligibility after index IO. */
public record SourceSearchScope(TenantId tenant, Map<UUID, SourceType> sources) {
    public SourceSearchScope { sources = Map.copyOf(sources); }
    public Set<SourceType> types() { return Set.copyOf(sources.values()); }
}
