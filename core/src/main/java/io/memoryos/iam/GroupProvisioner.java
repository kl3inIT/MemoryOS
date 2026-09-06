package io.memoryos.iam;

public interface GroupProvisioner {

    void bootstrap(TenantId tenantId, ActorId configuredOwner);

    void addToBasicGroup(TenantId tenantId, ActorId actorId);
}
