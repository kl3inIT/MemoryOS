package io.memoryos.invitation;

import io.memoryos.identity.ActorId;
import io.memoryos.organization.OrganizationId;

import java.util.List;
import java.util.UUID;

public interface InvitationService {

    IssuedInvitation issue(ActorId ownerActorId, String email);

    List<InvitationView> list(ActorId ownerActorId);

    IssuedInvitation rotate(ActorId ownerActorId, UUID invitationId);

    void revoke(ActorId ownerActorId, UUID invitationId);

    InvitationContinuation intake(String plaintextSecret);

    InvitationContinuation resume(UUID invitationId, OrganizationId organizationId);

    ActorId accept(InvitationAcceptance acceptance);

}
