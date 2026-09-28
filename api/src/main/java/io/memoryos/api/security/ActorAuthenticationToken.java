package io.memoryos.api.security;

import io.memoryos.iam.IdentityContext;

import java.io.Serial;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The application principal: a resolved MemoryOS actor. Bearer requests carry the validated JWT as
 * credentials; browser-session requests restore the token without one.
 */
@NullMarked
public final class ActorAuthenticationToken extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final IdentityContext identityContext;
    private final @Nullable Jwt jwt;

    public ActorAuthenticationToken(IdentityContext identityContext) {
        this(identityContext, null);
    }

    public ActorAuthenticationToken(IdentityContext identityContext, @Nullable Jwt jwt) {
        super(List.of());
        this.identityContext = Objects.requireNonNull(identityContext, "identityContext must not be null");
        this.jwt = jwt;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt == null ? "" : jwt.getTokenValue();
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
                || other instanceof ActorAuthenticationToken that
                && super.equals(that)
                && identityContext.equals(that.identityContext)
                && Objects.equals(jwt, that.jwt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), identityContext, jwt);
    }
}
