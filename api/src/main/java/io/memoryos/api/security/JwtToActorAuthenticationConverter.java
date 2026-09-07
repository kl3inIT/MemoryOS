package io.memoryos.api.security;

import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.ExternalIdentityResolver;
import io.memoryos.iam.IdentityContext;
import java.util.Objects;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;

@NullMarked
final class JwtToActorAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final ExternalIdentityResolver identityResolver;

    JwtToActorAuthenticationConverter(ExternalIdentityResolver identityResolver) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver must not be null");
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        var issuer = jwt.getIssuer();
        var subject = jwt.getSubject();
        if (issuer == null || subject == null || subject.isBlank()) {
            throw invalidToken("JWT issuer and subject are required");
        }
        var identity = new ExternalIdentity(issuer.toString(), subject);
        var actorId = identityResolver.resolve(identity)
                .orElseThrow(() -> invalidToken("Authenticated identity is not bound to a MemoryOS actor"));
        return new ActorAuthenticationToken(new IdentityContext(actorId), jwt);
    }

    private static OAuth2AuthenticationException invalidToken(String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(
                BearerTokenErrorCodes.INVALID_TOKEN,
                description,
                null));
    }
}
