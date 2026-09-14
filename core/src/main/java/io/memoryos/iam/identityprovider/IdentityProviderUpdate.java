package io.memoryos.iam.identityprovider;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Update payload for an existing identity provider. A null {@code alias} or {@code issuer} keeps the
 * stored value; a null {@code clientSecret} keeps the stored secret. Renaming the alias recreates
 * the provider under the new alias because Keycloak has no rename operation.
 */
public record IdentityProviderUpdate(
        @Nullable String alias,
        String displayName,
        @Nullable String issuer,
        String clientId,
        @Nullable String clientSecret,
        boolean enabled,
        boolean jitAllowed
) {

    public IdentityProviderUpdate {
        if (alias != null) {
            requireText(alias, "alias");
        }
        requireText(displayName, "displayName");
        if (issuer != null) {
            requireText(issuer, "issuer");
        }
        requireText(clientId, "clientId");
        if (clientSecret != null && clientSecret.isBlank()) {
            throw new IllegalArgumentException("clientSecret must not be blank");
        }
    }

    private static void requireText(@Nullable String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
