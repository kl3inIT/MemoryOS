package io.memoryos.iam.invitation;

public record IssuedInvitation(
        InvitationView invitation,
        String plaintextSecret,
        InvitationDelivery delivery
) {
}
