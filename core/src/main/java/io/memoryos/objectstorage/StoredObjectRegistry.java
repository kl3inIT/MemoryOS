package io.memoryos.objectstorage;

import io.memoryos.iam.tenant.TenantId;

public interface StoredObjectRegistry {
    void markDeletePending(TenantId tenantId, StoredObjectId storedObjectId);

    void remove(TenantId tenantId, StoredObjectId storedObjectId);
}
