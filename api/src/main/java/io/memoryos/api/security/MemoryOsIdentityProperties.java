package io.memoryos.api.security;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.identity")
public record MemoryOsIdentityProperties(String audience, List<Binding> bindings) {

    public MemoryOsIdentityProperties {
        requireText(audience, "audience");
        Objects.requireNonNull(bindings, "bindings must not be null");
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("bindings must not be empty");
        }
        bindings = List.copyOf(bindings);
    }

    public record Binding(String issuer, String subject, UUID actorId) {

        public Binding {
            requireText(issuer, "binding issuer");
            requireText(subject, "binding subject");
            Objects.requireNonNull(actorId, "binding actorId must not be null");
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
