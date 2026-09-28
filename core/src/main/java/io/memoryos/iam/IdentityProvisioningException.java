package io.memoryos.iam;

import io.memoryos.BusinessException;

public final class IdentityProvisioningException extends BusinessException {

    private final IdentityProvisioningFailureReason reason;

    public IdentityProvisioningException(IdentityProvisioningFailureReason reason, String diagnosticMessage) {
        super(reason, diagnosticMessage);
        this.reason = reason;
    }

    public IdentityProvisioningException(
            IdentityProvisioningFailureReason reason,
            String diagnosticMessage,
            Throwable cause
    ) {
        super(reason, diagnosticMessage, cause);
        this.reason = reason;
    }

    public IdentityProvisioningFailureReason reason() {
        return reason;
    }
}
