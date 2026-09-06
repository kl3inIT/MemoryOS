package io.memoryos.objectstorage;

import io.memoryos.tenant.TenantId;

public interface ObjectUploadService {
    ObjectUploadAuthorization initiate(TenantId tenantId, ObjectUploadSpecification specification);

    VerifiedObject verify(TenantId tenantId, ObjectUploadId uploadId);

    void adopt(TenantId tenantId, ObjectUploadId uploadId, ObjectVerificationToken token);

    void discard(TenantId tenantId, ObjectUploadId uploadId, ObjectVerificationToken token);

    void releaseAdopted(TenantId tenantId, ObjectUploadId uploadId);
}
