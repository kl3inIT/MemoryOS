package io.memoryos.iam.invitation;

import java.time.Instant;

public interface KeycloakRecipientProvisioner {

    KeycloakRecipientProvisioning provision(String normalizedEmail, Instant actionExpiresAt);
}
