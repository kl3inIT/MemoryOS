package io.memoryos;

import java.util.Objects;
import java.util.regex.Pattern;

public abstract class BusinessException extends RuntimeException {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

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
        this.code = requireCode(code);
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
        this.code = requireCode(code);
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

    private static String requireCode(String value) {
        requireText(value, "code");
        if (!CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "code must start with an uppercase letter and contain only uppercase letters, digits, or underscores"
            );
        }
        return value;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
