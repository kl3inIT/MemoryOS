package io.memoryos.objectstorage.s3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class S3ObjectStoragePropertiesTest {

    @Test
    void acceptsHttpsAndLoopbackHttpUploadEndpoints() {
        assertDoesNotThrow(() -> properties(URI.create("https://objects.example.test")));
        assertDoesNotThrow(() -> properties(URI.create("http://localhost:9000")));
        assertDoesNotThrow(() -> properties(URI.create("http://127.0.0.1:9000")));
        assertDoesNotThrow(() -> properties(URI.create("http://[::1]:9000")));
    }

    @Test
    void rejectsNonLoopbackHttpUploadEndpoint() {
        assertThrows(
                IllegalArgumentException.class,
                () -> properties(URI.create("http://objects.example.test"))
        );
    }

    private static S3ObjectStorageProperties properties(URI uploadEndpoint) {
        return new S3ObjectStorageProperties(
                URI.create("http://minio:9000"),
                uploadEndpoint,
                "us-east-1",
                "memoryos",
                "api-access",
                "api-secret",
                true,
                "system/readiness",
                Duration.ofMinutes(5),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10)
        );
    }
}
