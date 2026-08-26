package io.memoryos.organization;

import io.memoryos.identity.ExternalIdentity;

import java.util.Objects;
import java.util.regex.Pattern;

public record InitialOrganizationBootstrapRequest(
        ExternalIdentity ownerIdentity,
        String organizationSlug,
        String organizationDisplayName,
        String operatorChangeReference
) {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    public InitialOrganizationBootstrapRequest {
        Objects.requireNonNull(ownerIdentity, "ownerIdentity must not be null");
        requireOrganizationSlug(organizationSlug);
        requireText(organizationDisplayName, "organizationDisplayName");
        requireText(operatorChangeReference, "operatorChangeReference");
    }

    private static void requireOrganizationSlug(String value) {
        requireText(value, "organizationSlug");
        if (!SLUG.matcher(value).matches()) {
            throw new IllegalArgumentException("organizationSlug must be a lowercase DNS-style slug");
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
