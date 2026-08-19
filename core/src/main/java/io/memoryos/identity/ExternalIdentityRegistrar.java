package io.memoryos.identity;

public interface ExternalIdentityRegistrar {

    ActorId resolveOrCreate(ExternalIdentity identity);
}
