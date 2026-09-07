package io.memoryos.connector;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

public final class GoogleDriveException extends BusinessException {
    private GoogleDriveException(String code, FailureCategory category, String message) {
        super(code, category, message, message);
    }

    public static GoogleDriveException oauthClientRequired() {
        return new GoogleDriveException("GOOGLE_DRIVE_OAUTH_CLIENT_REQUIRED", FailureCategory.VALIDATION,
                "Upload Google Web OAuth client JSON before authorizing this source.");
    }

    public static GoogleDriveException invalidOAuthClient() {
        return new GoogleDriveException("GOOGLE_DRIVE_OAUTH_CLIENT_INVALID", FailureCategory.VALIDATION,
                "Supply valid Google Web OAuth client JSON containing the configured redirect URI.");
    }

    public static GoogleDriveException notConfigured() {
        return new GoogleDriveException("GOOGLE_DRIVE_NOT_CONFIGURED", FailureCategory.SERVICE_UNAVAILABLE,
                "Google Drive authorization is not configured. Contact the deployment owner.");
    }

    public static GoogleDriveException needsReauthorization() {
        return new GoogleDriveException("GOOGLE_DRIVE_NEEDS_REAUTHORIZATION", FailureCategory.CONFLICT,
                "Reconnect Google Drive before continuing.");
    }
}
