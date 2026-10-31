package io.memoryos.connector;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Authorization snapshot for one Search call. {@code accessTokens} are the reader's current index access tokens
 * ({@link DocumentAccess}); the repository still rechecks eligibility after index IO. {@code wholeSources}, a subset of
 * {@code sources}, are read whole whatever the reader may read of them: a public bot's attached Public and Private
 * Sources (MEM-298); empty for every other reader.
 */
public record SourceSearchScope(TenantId tenant, ActorId actor, Map<UUID, SourceType> sources, Set<String> accessTokens,
                                Set<UUID> wholeSources) {
    public SourceSearchScope {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(actor, "actor");
        sources = Map.copyOf(sources);
        accessTokens = Set.copyOf(accessTokens);
        wholeSources = Set.copyOf(wholeSources);
        if (!sources.keySet().containsAll(wholeSources)) throw new IllegalArgumentException("A whole Source is in scope.");
    }

    /** The reader's own scope: nothing is read beyond what the reader may. */
    public SourceSearchScope(TenantId tenant, ActorId actor, Map<UUID, SourceType> sources, Set<String> accessTokens) {
        this(tenant, actor, sources, accessTokens, Set.of());
    }

    /** Without reader tokens the index filter admits only public documents. */
    public SourceSearchScope(TenantId tenant, ActorId actor, Map<UUID, SourceType> sources) {
        this(tenant, actor, sources, Set.of());
    }

    public Set<SourceType> types() { return Set.copyOf(sources.values()); }
}
