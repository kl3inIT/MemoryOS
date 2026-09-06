package io.memoryos.api.tenant;

import java.util.Objects;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

@ConfigurationProperties("memoryos.initial-tenant")
record InitialTenantProperties(
        UUID id,
        String ownerSubject,
        String slug,
        String displayName,
        String changeReference
) {

    InitialTenantProperties {
        Objects.requireNonNull(id, "memoryos.initial-tenant.id must not be null");
        Assert.hasText(ownerSubject, "memoryos.initial-tenant.owner-subject must not be blank");
        Assert.hasText(slug, "memoryos.initial-tenant.slug must not be blank");
        Assert.hasText(displayName, "memoryos.initial-tenant.display-name must not be blank");
        Assert.hasText(changeReference, "memoryos.initial-tenant.change-reference must not be blank");
    }
}
