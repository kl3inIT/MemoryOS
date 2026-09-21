package io.memoryos.usage;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/** Safe AI cost report failures. */
public final class AiCostException extends BusinessException {
    private AiCostException(String code, FailureCategory category, String message) {
        super(code, category, message, message);
    }

    public static AiCostException invalid(String message) {
        return new AiCostException("AI_COST_INVALID", FailureCategory.VALIDATION, message);
    }

    /** A report that does not exist, belongs to another Tenant or is not ready to download. */
    public static AiCostException reportNotFound() {
        return new AiCostException("AI_USAGE_REPORT_NOT_FOUND", FailureCategory.NOT_FOUND, "The usage report is not available.");
    }
}
