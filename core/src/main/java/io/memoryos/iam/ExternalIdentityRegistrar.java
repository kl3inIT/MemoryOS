package io.memoryos.iam;

public interface ExternalIdentityRegistrar {

    ActorId resolveOrCreate(ExternalIdentity identity);

    ActorId resolveOrCreateLocked(ExternalIdentity identity);
}
