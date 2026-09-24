package io.memoryos.iam.identity;

import io.memoryos.shared.ActorId;

public interface ExternalIdentityRegistrar {

    ActorId resolveOrCreate(ExternalIdentity identity);

    ActorId resolveOrCreateLocked(ExternalIdentity identity);
}
