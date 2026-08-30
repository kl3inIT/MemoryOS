package io.memoryos.tenant;

import io.memoryos.identity.ExternalIdentity;

import java.util.Objects;
import java.util.regex.Pattern;

public record InitialTenantBootstrapRequest(
        TenantId tenantId,
        ExternalIdentity ownerIdentity,
        String tenantSlug,
        String tenantDisplayName,
        String operatorChangeReference
) {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

    public InitialTenantBootstrapRequest {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(ownerIdentity, "ownerIdentity must not be null");
        requireTenantSlug(tenantSlug);
        requireText(tenantDisplayName, "tenantDisplayName");
        requireText(operatorChangeReference, "operatorChangeReference");
    }

    private static void requireTenantSlug(String value) {
        requireText(value, "tenantSlug");
        if (!SLUG.matcher(value).matches()) {
            throw new IllegalArgumentException("tenantSlug must be a lowercase DNS-style slug");
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
