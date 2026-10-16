package io.memoryos.sandbox;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/** Expected failures of the sandbox, matched by code. */
public final class SandboxException extends BusinessException {
    private SandboxException(String code, FailureCategory category, String safeMessage, String diagnosticMessage,
                             Throwable cause) {
        super(code, category, safeMessage, diagnosticMessage, cause);
    }

    private SandboxException(String code, FailureCategory category, String safeMessage, String diagnosticMessage) {
        super(code, category, safeMessage, diagnosticMessage);
    }

    public static SandboxException notConfigured() {
        return new SandboxException("SANDBOX_NOT_CONFIGURED", FailureCategory.SERVICE_UNAVAILABLE,
                "The sandbox is not configured on this deployment.", "No sandbox runtime or image is configured");
    }

    /** Every sandbox the deployment allows is in use, as Glean and Dust bound background work. */
    public static SandboxException busy(int bound) {
        return new SandboxException("SANDBOX_BUSY", FailureCategory.LIMIT_EXCEEDED,
                "Every sandbox is in use. Try again in a few minutes.", "Running sandboxes reached " + bound);
    }

    public static SandboxException unavailable(Throwable cause) {
        return new SandboxException("SANDBOX_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "The sandbox did not answer. Try again in a moment.", "Sandbox runtime call failed", cause);
    }
}
