package io.memoryos.iam;


public interface TenantMemberManagement {

    void activate(ActorId administrator, ActorId target);

    void deactivate(ActorId administrator, ActorId target);
}
