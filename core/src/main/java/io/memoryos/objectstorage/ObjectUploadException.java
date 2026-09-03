package io.memoryos.objectstorage;

import io.memoryos.BusinessException;
import io.memoryos.FailureCategory;

public final class ObjectUploadException extends BusinessException {
    private ObjectUploadException(String code, FailureCategory category, String safeMessage, String diagnosticMessage) {
        super(code, category, safeMessage, diagnosticMessage);
    }
    private ObjectUploadException(
            String code,
            FailureCategory category,
            String safeMessage,
            String diagnosticMessage,
            Throwable cause
    ) {
        super(code, category, safeMessage, diagnosticMessage, cause);
    }


    public static ObjectUploadException notFound() {
        return new ObjectUploadException(
                "OBJECT_UPLOAD_NOT_FOUND",
                FailureCategory.NOT_FOUND,
                "The upload is unavailable.",
                "object upload was not found in the authorized Tenant"
        );
    }

    public static ObjectUploadException conflict(String diagnosticMessage) {
        return new ObjectUploadException(
                "OBJECT_UPLOAD_CONFLICT",
                FailureCategory.CONFLICT,
                "The upload cannot be finalized in its current state.",
                diagnosticMessage
        );
    }

    public static ObjectUploadException integrityMismatch() {
        return new ObjectUploadException(
                "OBJECT_UPLOAD_INTEGRITY_MISMATCH",
                FailureCategory.VALIDATION,
                "The uploaded file does not match its declared metadata.",
                "object storage metadata did not match the durable upload declaration"
        );
    }
    public static ObjectUploadException integrityMismatch(Throwable cause) {
        return new ObjectUploadException(
                "OBJECT_UPLOAD_INTEGRITY_MISMATCH",
                FailureCategory.VALIDATION,
                "The uploaded file does not match its declared metadata.",
                "object storage rejected the uploaded content",
                cause
        );
    }


    public static ObjectUploadException storageUnavailable(
            ObjectStorageFailureCode failureCode,
            Throwable cause
    ) {
        return new ObjectUploadException(
                "OBJECT_UPLOAD_STORAGE_UNAVAILABLE",
                FailureCategory.SERVICE_UNAVAILABLE,
                "Object storage is temporarily unavailable.",
                "object storage failed while handling an upload: " + failureCode,
                cause
        );
    }
}
