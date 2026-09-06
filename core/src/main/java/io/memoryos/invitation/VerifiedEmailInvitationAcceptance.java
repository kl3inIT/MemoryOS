package io.memoryos.invitation;

import io.memoryos.identity.ExternalIdentity;

public record VerifiedEmailInvitationAcceptance(
        ExternalIdentity externalIdentity,
        String email,
        boolean emailVerified
) {
}
