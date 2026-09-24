package io.memoryos.connector;

import io.memoryos.shared.ActorId;
import java.util.List;
import java.util.UUID;

/** Resolves a caller-authorized Source collection into a scope that can only narrow current Source access. */
public interface SourceCollectionScopeResolver {
    SourceSearchScope narrow(ActorId actor, List<UUID> collectionIds);
}
