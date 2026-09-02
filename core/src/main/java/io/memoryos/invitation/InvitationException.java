package io.memoryos.invitation;

import io.memoryos.BusinessException;

public final class InvitationException extends BusinessException {

    private final InvitationFailureReason reason;

    public InvitationException(InvitationFailureReason reason, String diagnosticMessage) {
        super(reason, diagnosticMessage);
        this.reason = reason;
    }

    public InvitationException(InvitationFailureReason reason, String diagnosticMessage, Throwable cause) {
        super(reason, diagnosticMessage, cause);
        this.reason = reason;
    }

    public InvitationFailureReason reason() {
        return reason;
    }
}
