package io.memoryos.iam;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Creation payload for an upstream OIDC identity provider. The alias is immutable and becomes the
 * broker path segment and the {@code memoryos_identity_provider} claim value.
 */
public record IdentityProviderCommand(
        String alias,
        String displayName,
        String issuer,
        String clientId,
        String clientSecret,
        boolean jitAllowed
) {

    public IdentityProviderCommand {
        requireText(alias, "alias");
        requireText(displayName, "displayName");
        requireText(issuer, "issuer");
        requireText(clientId, "clientId");
        requireText(clientSecret, "clientSecret");
    }

    private static void requireText(@Nullable String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
