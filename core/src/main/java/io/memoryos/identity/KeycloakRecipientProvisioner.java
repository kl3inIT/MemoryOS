package io.memoryos.identity;

import java.time.Instant;

public interface KeycloakRecipientProvisioner {

    KeycloakRecipientProvisioning provision(String normalizedEmail, Instant actionExpiresAt);
}
