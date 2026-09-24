package io.memoryos.iam.identity;

import io.memoryos.shared.ActorId;

import java.util.Optional;

public interface ExternalIdentityResolver {

    Optional<ActorId> resolve(ExternalIdentity identity);
}
