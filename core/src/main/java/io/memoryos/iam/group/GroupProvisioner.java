package io.memoryos.iam.group;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;

public interface GroupProvisioner {

    void bootstrap(TenantId tenantId, ActorId configuredOwner);

    void addToBasicGroup(TenantId tenantId, ActorId actorId);
}
