package io.memoryos;

import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Bounded, sanitized failure evidence for durable attempt/error rows. The stable machine code stays
 * the contract; message and detail carry the provider or extraction cause for operators. Secrets and
 * control characters never persist.
 */
public final class FailureEvidence {

    private static final int ERROR_MESSAGE_LIMIT = 512;
    private static final int ERROR_DETAIL_LIMIT = 4096;
    private static final Pattern SECRET_HINT = Pattern.compile(
            "(?i)(bearer\\s+\\S+|authorization\\s*:|access_token=|refresh_token=|client_secret=)");

    private FailureEvidence() {}

    /** Single-line bounded summary; secrets and control characters are stripped. */
    public static @Nullable String safeErrorMessage(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\t]]+", " ").trim();
        if (SECRET_HINT.matcher(cleaned).find()) return "Redacted provider error";
        return cleaned.length() <= ERROR_MESSAGE_LIMIT ? cleaned : cleaned.substring(0, ERROR_MESSAGE_LIMIT);
    }

    /** Multi-line bounded detail; secrets are stripped, newlines preserved. */
    public static @Nullable String safeErrorDetail(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\t\\n\\r]]+", "").trim();
        if (SECRET_HINT.matcher(cleaned).find()) return "Redacted provider error detail";
        return cleaned.length() <= ERROR_DETAIL_LIMIT ? cleaned : cleaned.substring(0, ERROR_DETAIL_LIMIT);
    }

    /** Bounded technical detail: exception class + message + stack frames. */
    public static @Nullable String detail(@Nullable Throwable exception) {
        if (exception == null) return null;
        var trace = new java.io.StringWriter();
        exception.printStackTrace(new java.io.PrintWriter(trace));
        String text = trace.toString();
        return text.length() <= ERROR_DETAIL_LIMIT ? text : text.substring(0, ERROR_DETAIL_LIMIT);
    }
}
