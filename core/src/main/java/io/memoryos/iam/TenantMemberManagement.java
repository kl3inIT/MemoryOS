package io.memoryos.iam;
import io.memoryos.shared.ActorId;


public interface TenantMemberManagement {

    void activate(ActorId administrator, ActorId target);

    void deactivate(ActorId administrator, ActorId target);
}
