package io.memoryos.connector;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

public final class SourceException extends BusinessException {

    private SourceException(String code, FailureCategory category, String safeMessage, String diagnosticMessage) {
        super(code, category, safeMessage, diagnosticMessage);
    }

    public static SourceException notOwner() {
        return new SourceException(
                "SOURCE_NOT_OWNER",
                FailureCategory.NOT_PERMITTED,
                "Only an active Organization owner can manage sources.",
                "source command denied because actor is not an active Organization owner"
        );
    }

    public static SourceException notFound() {
        return new SourceException(
                "SOURCE_NOT_FOUND",
                FailureCategory.NOT_FOUND,
                "The source or item is unavailable.",
                "source resource was not found in the authorized Organization"
        );
    }

    public static SourceException conflict(String diagnosticMessage) {
        return new SourceException(
                "SOURCE_CONFLICT",
                FailureCategory.CONFLICT,
                "The source cannot accept that operation in its current state.",
                diagnosticMessage
        );
    }

    public static SourceException invalid(String safeMessage, String diagnosticMessage) {
        return new SourceException(
                "SOURCE_INVALID_REQUEST",
                FailureCategory.VALIDATION,
                safeMessage,
                diagnosticMessage
        );
    }
}
