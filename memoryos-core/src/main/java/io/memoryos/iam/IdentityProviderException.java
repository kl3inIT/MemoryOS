package io.memoryos.iam;

import io.memoryos.BusinessException;

public final class IdentityProviderException extends BusinessException {

    public IdentityProviderException(IdentityProviderFailureReason reason, String diagnosticMessage) {
        super(reason, diagnosticMessage);
    }

    public IdentityProviderException(
            IdentityProviderFailureReason reason,
            String diagnosticMessage,
            Throwable cause
    ) {
        super(reason, diagnosticMessage, cause);
    }
}
