package io.memoryos.chat;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.StoredObjectReference;
import java.util.UUID;

public record UserFileWork(TenantId tenantId, ActorId owner, UUID operationId, UUID fileId, UUID token,
        Action action, int attempts, StoredObjectReference object) {
    public enum Action { PROCESS, DELETE }
}
