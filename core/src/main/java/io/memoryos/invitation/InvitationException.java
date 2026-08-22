package io.memoryos.invitation;

import java.util.Objects;

public final class InvitationException extends RuntimeException {

    private final InvitationFailureReason reason;

    public InvitationException(InvitationFailureReason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public InvitationFailureReason reason() {
        return reason;
    }

}
