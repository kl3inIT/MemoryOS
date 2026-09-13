package io.memoryos.iam.tenant;

import java.util.Optional;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.invitation.InvitationTarget;

public interface TenantMembershipProvisioner {

    Optional<InvitationTarget> findActiveInvitationTarget(TenantId tenantId);

    boolean hasAnyMembership(ActorId actorId);

    void grantMember(TenantId tenantId, ActorId actorId);
}
