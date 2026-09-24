package io.memoryos.connector;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Authorization snapshot for one Search call. {@code accessTokens} are the reader's current index access tokens
 * ({@link DocumentAccess}); the repository still rechecks eligibility after index IO.
 */
public record SourceSearchScope(TenantId tenant, ActorId actor, Map<UUID, SourceType> sources, Set<String> accessTokens) {
    public SourceSearchScope {
        java.util.Objects.requireNonNull(tenant, "tenant");
        java.util.Objects.requireNonNull(actor, "actor");
        sources = Map.copyOf(sources);
        accessTokens = Set.copyOf(accessTokens);
    }

    /** Without reader tokens the index filter admits only public documents. */
    public SourceSearchScope(TenantId tenant, ActorId actor, Map<UUID, SourceType> sources) {
        this(tenant, actor, sources, Set.of());
    }

    public Set<SourceType> types() { return Set.copyOf(sources.values()); }
}
