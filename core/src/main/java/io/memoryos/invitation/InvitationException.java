package io.memoryos.invitation;

import io.memoryos.BusinessException;

import java.util.Objects;

public final class InvitationException extends BusinessException {

    private final InvitationFailureReason reason;

    public InvitationException(InvitationFailureReason reason, String diagnosticMessage) {
        super(
                requireReason(reason).code(),
                reason.category(),
                reason.message(),
                diagnosticMessage
        );
        this.reason = reason;
    }

    public InvitationException(
            InvitationFailureReason reason,
            String diagnosticMessage,
            Throwable cause
    ) {
        super(
                requireReason(reason).code(),
                reason.category(),
                reason.message(),
                diagnosticMessage,
                cause
        );
        this.reason = reason;
    }

    public InvitationFailureReason reason() {
        return reason;
    }

    private static InvitationFailureReason requireReason(InvitationFailureReason reason) {
        return Objects.requireNonNull(reason, "reason must not be null");
    }
}
