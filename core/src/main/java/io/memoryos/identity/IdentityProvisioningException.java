package io.memoryos.identity;

import io.memoryos.BusinessException;

import java.util.Objects;

public final class IdentityProvisioningException extends BusinessException {

    private final IdentityProvisioningFailureReason reason;

    public IdentityProvisioningException(
            IdentityProvisioningFailureReason reason,
            String diagnosticMessage
    ) {
        super(
                requireReason(reason).code(),
                reason.category(),
                reason.message(),
                diagnosticMessage
        );
        this.reason = reason;
    }

    public IdentityProvisioningException(
            IdentityProvisioningFailureReason reason,
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

    public IdentityProvisioningFailureReason reason() {
        return reason;
    }

    private static IdentityProvisioningFailureReason requireReason(
            IdentityProvisioningFailureReason reason
    ) {
        return Objects.requireNonNull(reason, "reason must not be null");
    }
}
