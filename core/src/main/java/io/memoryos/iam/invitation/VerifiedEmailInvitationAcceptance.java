package io.memoryos.iam.invitation;
import io.memoryos.iam.identity.ExternalIdentity;

public record VerifiedEmailInvitationAcceptance(
        ExternalIdentity externalIdentity,
        String email,
        boolean emailVerified
) {
}
