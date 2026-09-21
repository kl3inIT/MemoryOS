package io.memoryos.objectstorage;

import io.memoryos.iam.tenant.TenantId;

public interface ObjectUploadService {
    ObjectUploadAuthorization initiate(TenantId tenantId, ObjectUploadSpecification specification);

    /**
     * Writes bytes the server already holds as a verified upload, for the caller to adopt in its own transaction.
     * It follows the browser path's lifecycle, so an upload never adopted is reclaimed by the same cleanup.
     */
    VerifiedObject write(TenantId tenantId, ObjectUploadSpecification specification, byte[] content);

    VerifiedObject verify(TenantId tenantId, ObjectUploadId uploadId);

    VerifiedObject verify(TenantId tenantId, ObjectUploadId uploadId, ObjectUploadPurpose purpose);

    ObjectUploadAuthorization resume(TenantId tenantId, ObjectUploadId uploadId, ObjectUploadPurpose purpose);

    void adopt(TenantId tenantId, ObjectUploadId uploadId, ObjectVerificationToken token);

    void discard(TenantId tenantId, ObjectUploadId uploadId, ObjectVerificationToken token);

    void releaseAdopted(TenantId tenantId, ObjectUploadId uploadId);

    /** Retain the upload receipt and schedule raw bytes for the existing cleanup worker. */
    void retireAdopted(TenantId tenantId, ObjectUploadId uploadId);
}
