package io.memoryos.objectstorage.s3;

import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectStorageException;

import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@ConditionalOnEnabledHealthIndicator("object-storage")
@Component("objectStorage")
final class ObjectStorageHealthIndicator implements HealthIndicator {
    private final S3ObjectStorage storage;
    private final ObjectKey readinessKey;

    ObjectStorageHealthIndicator(S3ObjectStorage storage, S3ObjectStorageProperties properties) {
        this.storage = storage;
        this.readinessKey = new ObjectKey(properties.readinessKey());
    }

    @Override
    public Health health() {
        try {
            storage.verifyReadable(readinessKey);
            return Health.up().build();
        } catch (ObjectStorageException exception) {
            return Health.down()
                    .withDetail("failureCode", exception.code().name())
                    .build();
        }
    }
}
