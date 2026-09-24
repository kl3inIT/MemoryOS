package io.memoryos.iam.group;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;

public interface GroupProvisioner {

    void bootstrap(TenantId tenantId, ActorId configuredOwner);

    void addToBasicGroup(TenantId tenantId, ActorId actorId);
}
