package io.memoryos.iam;

import java.util.Optional;

public interface ExternalIdentityResolver {

    Optional<ActorId> resolve(ExternalIdentity identity);
}
