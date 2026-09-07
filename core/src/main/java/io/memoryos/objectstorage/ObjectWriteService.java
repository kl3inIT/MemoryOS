package io.memoryos.objectstorage;

import io.memoryos.tenant.TenantId;
import java.util.UUID;

public interface ObjectWriteService {
    StagedObject stage(TenantId tenantId, Specification specification, byte[] bytes);
    void adopt(TenantId tenantId, StagedObject staged);
    void discard(TenantId tenantId, StagedObject staged);
    void releaseAdopted(TenantId tenantId, StoredObjectId objectId);
    int cleanup(int limit);

    record Specification(String filename, String mediaType, boolean nativeSnapshot) {}
    record StagedObject(StoredObjectReference object, UUID token) {}
}
