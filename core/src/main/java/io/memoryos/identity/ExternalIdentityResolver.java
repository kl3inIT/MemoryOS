package io.memoryos.identity;

import java.util.Optional;

public interface ExternalIdentityResolver {

    Optional<ActorId> resolve(ExternalIdentity identity);
}
