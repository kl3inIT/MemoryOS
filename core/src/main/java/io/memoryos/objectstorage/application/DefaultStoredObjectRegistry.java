package io.memoryos.objectstorage.application;

import io.memoryos.objectstorage.StoredObjectId;
import io.memoryos.objectstorage.StoredObjectRegistry;
import io.memoryos.objectstorage.persistence.JdbcStoredObjectRepository;
import io.memoryos.tenant.TenantId;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultStoredObjectRegistry implements StoredObjectRegistry {
    private final JdbcStoredObjectRepository objects;

    public DefaultStoredObjectRegistry(JdbcStoredObjectRepository objects) {
        this.objects = Objects.requireNonNull(objects, "objects must not be null");
    }

    @Override
    @Transactional
    public void markDeletePending(TenantId tenantId, StoredObjectId storedObjectId) {
        objects.markDeletePending(tenantId, storedObjectId);
    }

    @Override
    @Transactional
    public void remove(TenantId tenantId, StoredObjectId storedObjectId) {
        objects.remove(tenantId, storedObjectId);
    }
}
