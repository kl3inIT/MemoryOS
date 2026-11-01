package io.memoryos.objectstorage.s3;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.object-storage.s3")
public record S3ObjectStorageProperties(
        URI serviceEndpoint,
        URI uploadEndpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        boolean pathStyleAccess,
        String readinessKey,
        Duration authorizationLifetime,
        Duration connectTimeout,
        Duration readTimeout
) {
    public S3ObjectStorageProperties {
        requireHttpEndpoint(serviceEndpoint, "serviceEndpoint");
        requireBrowserUploadEndpoint(uploadEndpoint);
        requireText(region, "region");
        requireText(bucket, "bucket");
        requireText(accessKey, "accessKey");
        requireText(secretKey, "secretKey");
        requireText(readinessKey, "readinessKey");
        requirePositive(authorizationLifetime, "authorizationLifetime");
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
        if (authorizationLifetime.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("authorizationLifetime must not exceed one hour");
        }
    }

    private static void requireHttpEndpoint(URI value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (!("http".equals(value.getScheme()) || "https".equals(value.getScheme())) || value.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an absolute HTTP endpoint");
        }
    }
    private static void requireBrowserUploadEndpoint(URI value) {
        requireHttpEndpoint(value, "uploadEndpoint");
        if ("http".equals(value.getScheme()) && !isLoopback(value.getHost())) {
            throw new IllegalArgumentException("uploadEndpoint must use HTTPS unless it targets loopback");
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "::1".equals(host)
                || "[::1]".equals(host)
                || host.startsWith("127.");
    }


    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
