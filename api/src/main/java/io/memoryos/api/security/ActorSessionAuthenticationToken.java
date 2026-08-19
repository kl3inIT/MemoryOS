package io.memoryos.api.security;

import io.memoryos.identity.IdentityContext;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;

@NullMarked
final class ActorSessionAuthenticationToken extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final IdentityContext identityContext;

    ActorSessionAuthenticationToken(IdentityContext identityContext) {
        super(List.of());
        this.identityContext = Objects.requireNonNull(identityContext, "identityContext must not be null");
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
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
        return this == other
                || other instanceof ActorSessionAuthenticationToken that
                && super.equals(that)
                && identityContext.equals(that.identityContext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), identityContext);
    }
}
