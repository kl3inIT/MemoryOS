package io.memoryos.iam.audit;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/** Safe failures of reading the audit stream. */
public final class AuditException extends BusinessException {
    private AuditException(String code, FailureCategory category, String message) {
        super(code, category, message, message);
    }

    public static AuditException invalid(String message) {
        return new AuditException("AUDIT_INVALID", FailureCategory.VALIDATION, message);
    }

    public static AuditException notFound() {
        return new AuditException("AUDIT_EVENT_NOT_FOUND", FailureCategory.NOT_FOUND, "The audit event is not available.");
    }
}
