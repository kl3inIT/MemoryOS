package io.memoryos.api.invitation;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InvitationSessionState(
        UUID invitationId,
        UUID organizationId,
        Instant expiresAt,
        String nonce
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ATTRIBUTE = InvitationSessionState.class.getName();

    public InvitationSessionState {
        Objects.requireNonNull(invitationId, "invitationId must not be null");
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (nonce == null || nonce.isBlank()) {
            throw new IllegalArgumentException("nonce must not be blank");
        }
    }
}
