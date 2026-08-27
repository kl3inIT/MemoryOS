package io.memoryos.identity;

import java.time.Instant;

public interface KeycloakInvitationProvisioner {

    KeycloakRecipientProvisioning provision(String normalizedEmail, Instant actionExpiresAt);
}
