package io.memoryos.objectstorage;

import io.memoryos.iam.TenantId;

public interface ObjectUploadService {
    ObjectUploadAuthorization initiate(TenantId tenantId, ObjectUploadSpecification specification);

    VerifiedObject verify(TenantId tenantId, ObjectUploadId uploadId);

    VerifiedObject verify(TenantId tenantId, ObjectUploadId uploadId, ObjectUploadPurpose purpose);

    ObjectUploadAuthorization resume(TenantId tenantId, ObjectUploadId uploadId, ObjectUploadPurpose purpose);

    void adopt(TenantId tenantId, ObjectUploadId uploadId, ObjectVerificationToken token);

    void discard(TenantId tenantId, ObjectUploadId uploadId, ObjectVerificationToken token);

    void releaseAdopted(TenantId tenantId, ObjectUploadId uploadId);

    /** Retain the upload receipt and schedule raw bytes for the existing cleanup worker. */
    void retireAdopted(TenantId tenantId, ObjectUploadId uploadId);
}
