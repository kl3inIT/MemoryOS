package io.memoryos.iam;

import io.memoryos.BusinessException;

public final class McpClientGrantException extends BusinessException {

    public McpClientGrantException(McpClientGrantFailureReason reason, String diagnosticMessage) {
        super(reason, diagnosticMessage);
    }

    public McpClientGrantException(McpClientGrantFailureReason reason, String diagnosticMessage, Throwable cause) {
        super(reason, diagnosticMessage, cause);
    }
}
