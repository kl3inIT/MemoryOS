package io.memoryos.invitation;

public record IssuedInvitation(InvitationView invitation, String plaintextSecret) {
}
