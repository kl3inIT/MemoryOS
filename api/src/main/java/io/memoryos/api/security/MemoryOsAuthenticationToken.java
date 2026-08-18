package io.memoryos.api.security;

import io.memoryos.identity.IdentityContext;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

@NullMarked
final class MemoryOsAuthenticationToken extends AbstractAuthenticationToken {

    private final Jwt jwt;
    private final IdentityContext identityContext;

    MemoryOsAuthenticationToken(Jwt jwt, IdentityContext identityContext) {
        super(List.of());
        this.jwt = Objects.requireNonNull(jwt, "jwt must not be null");
        this.identityContext = Objects.requireNonNull(identityContext, "identityContext must not be null");
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt.getTokenValue();
    }

    @Override
    public IdentityContext getPrincipal() {
        return identityContext;
    }

    @Override
    public String getName() {
        return identityContext.actorId().value().toString();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MemoryOsAuthenticationToken that)) {
            return false;
        }
        return super.equals(that)
                && jwt.equals(that.jwt)
                && identityContext.equals(that.identityContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), jwt, identityContext);
    }
}
