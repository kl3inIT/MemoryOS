package io.memoryos.iam;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/** Endpoints resolved from an upstream issuer's OpenID Connect discovery document. */
public record DiscoveredOidcProvider(
        String issuer,
        String authorizationUrl,
        String tokenUrl,
        @Nullable String logoutUrl,
        @Nullable String userInfoUrl,
        String jwksUrl
) {

    public DiscoveredOidcProvider {
        requireText(issuer, "issuer");
        requireText(authorizationUrl, "authorizationUrl");
        requireText(tokenUrl, "tokenUrl");
        requireText(jwksUrl, "jwksUrl");
    }

    private static void requireText(@Nullable String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
