package io.memoryos;

import java.util.Objects;

public abstract class BusinessException extends RuntimeException {

    private final String code;
    private final FailureCategory category;
    private final String safeMessage;

    protected BusinessException(
            String code,
            FailureCategory category,
            String safeMessage,
            String diagnosticMessage
    ) {
        super(diagnosticMessage);
        this.code = requireText(code, "code");
        this.category = Objects.requireNonNull(category, "category must not be null");
        this.safeMessage = requireText(safeMessage, "safeMessage");
    }

    protected BusinessException(
            String code,
            FailureCategory category,
            String safeMessage,
            String diagnosticMessage,
            Throwable cause
    ) {
        super(diagnosticMessage, cause);
        this.code = requireText(code, "code");
        this.category = Objects.requireNonNull(category, "category must not be null");
        this.safeMessage = requireText(safeMessage, "safeMessage");
    }

    public String code() {
        return code;
    }

    public FailureCategory category() {
        return category;
    }

    public String safeMessage() {
        return safeMessage;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
