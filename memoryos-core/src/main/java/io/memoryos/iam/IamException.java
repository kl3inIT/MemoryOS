package io.memoryos.iam;

import io.memoryos.BusinessException;

public final class IamException extends BusinessException {

    public IamException(IamFailureReason reason, String diagnosticMessage) {
        super(reason, diagnosticMessage);
    }

    public IamException(IamFailureReason reason, String diagnosticMessage, Throwable cause) {
        super(reason, diagnosticMessage, cause);
    }
}
