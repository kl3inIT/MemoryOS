package io.memoryos.iam;

import io.memoryos.BusinessException;

public final class McpClientPolicyException extends BusinessException {

    public McpClientPolicyException(McpClientPolicyFailureReason reason, String diagnosticMessage, Throwable cause) {
        super(reason, diagnosticMessage, cause);
    }
}
