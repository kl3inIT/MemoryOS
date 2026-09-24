package io.memoryos.iam.tenant;

import io.memoryos.shared.TenantId;

import java.util.Optional;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.invitation.InvitationTarget;

public interface TenantMembershipProvisioner {

    Optional<InvitationTarget> findActiveInvitationTarget(TenantId tenantId);

    boolean hasAnyMembership(ActorId actorId);

    void grantMember(TenantId tenantId, ActorId actorId);
}
