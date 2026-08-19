package io.memoryos.api.security;

import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("memoryos.initial-organization")
record MemoryOsInitialOrganizationProperties(
        String ownerSubject,
        String slug,
        String displayName,
        String defaultWorkspaceSlug,
        String defaultWorkspaceDisplayName,
        String changeReference
) {

    MemoryOsInitialOrganizationProperties {
        requireText(ownerSubject, "memoryos.initial-organization.owner-subject");
        requireText(slug, "memoryos.initial-organization.slug");
        requireText(displayName, "memoryos.initial-organization.display-name");
        requireText(defaultWorkspaceSlug, "memoryos.initial-organization.default-workspace-slug");
        requireText(defaultWorkspaceDisplayName, "memoryos.initial-organization.default-workspace-display-name");
        requireText(changeReference, "memoryos.initial-organization.change-reference");
    }

    private static void requireText(String value, String property) {
        Objects.requireNonNull(value, property + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(property + " must not be blank");
        }
    }
}