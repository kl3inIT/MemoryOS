package io.memoryos.api.security;

import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.identity")
public record MemoryOsIdentityProperties(String audience) {

    public MemoryOsIdentityProperties {
        Objects.requireNonNull(audience, "audience must not be null");
        if (audience.isBlank()) {
            throw new IllegalArgumentException("audience must not be blank");
        }
    }
}
