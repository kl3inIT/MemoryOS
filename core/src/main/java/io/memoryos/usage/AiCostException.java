package io.memoryos.usage;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

/** Safe AI cost report failures. */
public final class AiCostException extends BusinessException {
    private AiCostException(String message) {
        super("AI_COST_INVALID", FailureCategory.VALIDATION, message, message);
    }

    public static AiCostException invalid(String message) { return new AiCostException(message); }
}
