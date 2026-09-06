package io.memoryos.iam;

public record IssuedInvitation(
        InvitationView invitation,
        String plaintextSecret,
        InvitationDelivery delivery
) {
}
