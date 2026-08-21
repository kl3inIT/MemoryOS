package io.memoryos.invitation;

import java.util.Objects;

public final class OrganizationInvitationException extends RuntimeException {

    private final Reason reason;

    public OrganizationInvitationException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        NOT_OWNER,
        INVALID_EMAIL,
        INVITATION_CONFLICT,
        INVITATION_NOT_AVAILABLE,
        EMAIL_NOT_VERIFIED,
        EMAIL_MISMATCH,
        IDENTITY_CONFLICT
    }
}
