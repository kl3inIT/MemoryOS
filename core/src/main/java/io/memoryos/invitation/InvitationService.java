package io.memoryos.invitation;

import io.memoryos.identity.ActorId;
import io.memoryos.tenant.TenantId;

import java.util.UUID;

public interface InvitationService {

    IssuedInvitation issue(ActorId ownerActorId, String email);

    InvitationPage list(ActorId ownerActorId, InvitationQuery query);

    IssuedInvitation rotate(ActorId ownerActorId, UUID invitationId);

    void revoke(ActorId ownerActorId, UUID invitationId);

    InvitationContinuation intake(String plaintextSecret);

    InvitationContinuation resume(UUID invitationId, TenantId tenantId);

    ActorId accept(InvitationAcceptance acceptance);

    ActorId acceptVerifiedEmail(VerifiedEmailInvitationAcceptance acceptance);

}
