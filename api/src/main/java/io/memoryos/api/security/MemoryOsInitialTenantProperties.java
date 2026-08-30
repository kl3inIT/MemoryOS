package io.memoryos.api.security;

import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.initial-tenant")
record MemoryOsInitialTenantProperties(
        UUID id,
        String ownerSubject,
        String slug,
        String displayName,
        String changeReference
) {

    MemoryOsInitialTenantProperties {
        Objects.requireNonNull(id, "memoryos.initial-tenant.id must not be null");
        requireText(ownerSubject, "memoryos.initial-tenant.owner-subject");
        requireText(slug, "memoryos.initial-tenant.slug");
        requireText(displayName, "memoryos.initial-tenant.display-name");
        requireText(changeReference, "memoryos.initial-tenant.change-reference");
    }

    private static void requireText(String value, String property) {
        Objects.requireNonNull(value, property + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be blank");
        }
    }
}