package io.memoryos.iam;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Safe projection of a Keycloak OIDC identity provider. Never carries the client secret.
 * {@code jitAllowed} reflects the durable MemoryOS allowlist, not Keycloak state. Issuer and
 * clientId may be blank when the provider was configured outside MemoryOS.
 */
public record IdentityProviderView(
        String alias,
        String displayName,
        String issuer,
        String clientId,
        boolean enabled,
        boolean jitAllowed
) {

    public IdentityProviderView {
        requireText(alias, "alias");
        requireText(displayName, "displayName");
        Objects.requireNonNull(issuer, "issuer must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
    }

    private static void requireText(@Nullable String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
