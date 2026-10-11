package io.memoryos.connector;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

public final class LarkException extends BusinessException {
    private LarkException(String code, FailureCategory category, String safeMessage, String diagnosticMessage) {
        super(code, category, safeMessage, diagnosticMessage);
    }

    public static LarkException notConfigured() {
        return new LarkException("SOURCE_LARK_NOT_CONFIGURED", FailureCategory.SERVICE_UNAVAILABLE,
                "Lark credentials are not configured. Contact the deployment owner.",
                "Lark credential encryption key is missing or unusable");
    }

    public static LarkException invalidAppId() {
        return new LarkException("SOURCE_LARK_APP_ID_INVALID", FailureCategory.VALIDATION,
                "Supply the App ID of the Lark app, which starts with cli_.", "Lark App ID is malformed");
    }

    public static LarkException invalidSecret() {
        return new LarkException("SOURCE_LARK_SECRET_INVALID", FailureCategory.VALIDATION,
                "Supply the App Secret, between 1 and 256 characters.", "Lark App Secret is empty or too long");
    }

    /** Every rejection code, so a caller can recognise one without listing them again. */
    public static boolean isCredentialRejection(String code) {
        return code != null && code.startsWith("SOURCE_LARK_CREDENTIAL_");
    }

    /** Lark refused the App ID or the App Secret itself, so nothing is stored. */
    public static LarkException rejected() {
        return new LarkException("SOURCE_LARK_CREDENTIAL_REJECTED", FailureCategory.VALIDATION,
                "Lark rejected the App ID or the App Secret. Copy both from the app's Credentials page.",
                "Lark rejected the app credential");
    }

    /** The app works but Lark refuses what was asked to its tenant token. */
    public static LarkException scopeMissing() {
        return new LarkException("SOURCE_LARK_SCOPE_MISSING", FailureCategory.CONFLICT,
                "The Lark app lacks a permission for this. Grant the scopes the credential lists, then publish the app.",
                "Lark refused a scope to the tenant token");
    }

    public static LarkException throttled() {
        return new LarkException("SOURCE_LARK_QUOTA", FailureCategory.SERVICE_UNAVAILABLE,
                "Lark is limiting requests from this app. Try again later.", "Lark throttled the request");
    }

    public static LarkException unavailable() {
        return new LarkException("SOURCE_LARK_UNAVAILABLE", FailureCategory.SERVICE_UNAVAILABLE,
                "Lark did not answer. Try again in a moment.", "Lark provider request did not complete");
    }

    public static LarkException needsUpdate() {
        return new LarkException("SOURCE_LARK_NEEDS_UPDATE", FailureCategory.CONFLICT,
                "Update the Lark credential before continuing.", "Lark credential is marked NEEDS_UPDATE");
    }
}
