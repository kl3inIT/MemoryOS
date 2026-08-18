package io.memoryos.identity;

public interface ExternalIdentityBindingProvisioner {

    ProvisioningResult provision(ExternalIdentity identity, ActorId actorId);

    enum ProvisioningResult {
        CREATED,
        UNCHANGED
    }
}
