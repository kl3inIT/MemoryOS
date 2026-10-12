package io.memoryos.objectstorage.application;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.object-storage.upload")
public record ObjectUploadProperties(
        Duration lifetime,
        Duration verificationLease,
        Duration adoptionTimeout,
        Duration cleanupLease,
        int cleanupBatchSize
) {
    public ObjectUploadProperties {
        requirePositive(lifetime, "lifetime");
        requirePositive(verificationLease, "verificationLease");
        requirePositive(adoptionTimeout, "adoptionTimeout");
        requirePositive(cleanupLease, "cleanupLease");
        if (cleanupBatchSize < 1 || cleanupBatchSize > 100) {
            throw new IllegalArgumentException("cleanupBatchSize must be between 1 and 100");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
