package io.memoryos.tenant;

import io.memoryos.identity.ExternalIdentity;

import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.util.Assert;

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
        Assert.hasText(tenantSlug, "tenantSlug must not be blank");
        if (!SLUG.matcher(tenantSlug).matches()) {
            throw new IllegalArgumentException("tenantSlug must be a lowercase DNS-style slug");
        }
        Assert.hasText(tenantDisplayName, "tenantDisplayName must not be blank");
        Assert.hasText(operatorChangeReference, "operatorChangeReference must not be blank");
    }
}
