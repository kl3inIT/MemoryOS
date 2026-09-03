package io.memoryos.objectstorage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record UploadAuthorization(String method, URI uri, Map<String, String> requiredHeaders, Instant expiresAt) {
    public UploadAuthorization {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(uri, "uri must not be null");
        requiredHeaders = Map.copyOf(Objects.requireNonNull(requiredHeaders, "requiredHeaders must not be null"));
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    }
}
