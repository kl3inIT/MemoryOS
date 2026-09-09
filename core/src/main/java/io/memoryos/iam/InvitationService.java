package io.memoryos.iam;


import java.util.UUID;

public interface InvitationService {

    IssuedInvitation issue(ActorId administrator, String email);

    InvitationPage list(ActorId administrator, InvitationQuery query);

    IssuedInvitation rotate(ActorId administrator, UUID invitationId);

    void revoke(ActorId administrator, UUID invitationId);

    InvitationContinuation intake(String plaintextSecret);

    InvitationContinuation resume(UUID invitationId, TenantId tenantId);

    ActorId accept(InvitationAcceptance acceptance);

    ActorId acceptVerifiedEmail(VerifiedEmailInvitationAcceptance acceptance);

}
