package io.memoryos.api.invitation;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InvitationSessionState(
        UUID invitationId,
        UUID tenantId,
        Instant expiresAt
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ATTRIBUTE = InvitationSessionState.class.getName();
    public static final String ACTIVATION_ATTRIBUTE = ATTRIBUTE + ".activation";

    public InvitationSessionState {
        Objects.requireNonNull(invitationId, "invitationId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
