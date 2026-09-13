package io.memoryos.iam.invitation;


import java.util.UUID;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;

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
