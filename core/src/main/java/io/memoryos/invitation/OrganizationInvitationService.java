package io.memoryos.invitation;

import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.organization.OrganizationId;
import io.memoryos.organization.WorkspaceId;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OrganizationInvitationService {

    IssuedInvitation issue(ActorId ownerActorId, String email);

    List<InvitationView> list(ActorId ownerActorId);

    IssuedInvitation rotate(ActorId ownerActorId, UUID invitationId);

    void revoke(ActorId ownerActorId, UUID invitationId);

    InvitationContinuation intake(String plaintextSecret);
    InvitationContinuation resume(UUID invitationId, OrganizationId organizationId);


    ActorId accept(InvitationAcceptance acceptance);

    enum Status {
        PENDING,
        ACCEPTED,
        EXPIRED,
        REVOKED
    }

    record InvitationView(
            UUID id,
            OrganizationId organizationId,
            WorkspaceId defaultWorkspaceId,
            String email,
            Status status,
            int secretVersion,
            Instant createdAt,
            Instant expiresAt,
            ActorId acceptedActorId,
            Instant acceptedAt,
            Instant revokedAt
    ) {
    }

    record IssuedInvitation(InvitationView invitation, String plaintextSecret) {
    }

    record InvitationContinuation(
            UUID invitationId,
            OrganizationId organizationId,
            String organizationDisplayName,
            Instant expiresAt
    ) {
    }

    record InvitationAcceptance(
            UUID invitationId,
            OrganizationId organizationId,
            ExternalIdentity externalIdentity,
            String email,
            boolean emailVerified
    ) {
    }
}
