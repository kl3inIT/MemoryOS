package io.memoryos.iam;

public record VerifiedEmailInvitationAcceptance(
        ExternalIdentity externalIdentity,
        String email,
        boolean emailVerified
) {
}
