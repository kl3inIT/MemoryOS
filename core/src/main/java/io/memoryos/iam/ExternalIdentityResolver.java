package io.memoryos.iam;

import io.memoryos.shared.ActorId;

import java.util.List;
import java.util.Optional;

public interface ExternalIdentityResolver {

    Optional<ActorId> resolve(ExternalIdentity identity);

    /** Every identity bound to the actor, oldest first. */
    List<ExternalIdentity> identities(ActorId actor);
}
