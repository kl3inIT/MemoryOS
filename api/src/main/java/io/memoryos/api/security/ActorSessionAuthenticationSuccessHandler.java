package io.memoryos.api.security;

import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.identity.IdentityContext;
import io.memoryos.organization.OrganizationAccessResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

final class ActorSessionAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final String AUTHENTICATED_DESTINATION = "/";
    private static final String ACCESS_NOT_PROVISIONED_DESTINATION = "/access-not-provisioned";

    private final ExternalIdentityResolver identityResolver;
    private final OrganizationAccessResolver organizationAccessResolver;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    ActorSessionAuthenticationSuccessHandler(
            ExternalIdentityResolver identityResolver,
            OrganizationAccessResolver organizationAccessResolver
    ) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver must not be null");
        this.organizationAccessResolver = Objects.requireNonNull(
                organizationAccessResolver,
                "organizationAccessResolver must not be null"
        );
    }

    @Override
    public void onAuthenticationSuccess(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Authentication authentication
    ) throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2)
                || !(oauth2.getPrincipal() instanceof OidcUser oidcUser)) {
            reject(request, response);
            return;
        }

        var issuer = oidcUser.getIssuer();
        String subject = oidcUser.getSubject();
        if (issuer == null || subject == null || subject.isBlank()) {
            reject(request, response);
            return;
        }

        var actorId = identityResolver.resolve(new ExternalIdentity(issuer.toString(), subject)).orElse(null);
        if (actorId == null || !organizationAccessResolver.hasActiveOrganization(actorId)) {
            reject(request, response);
            return;
        }

        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new ActorSessionAuthenticationToken(new IdentityContext(actorId)));
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);
        redirectStrategy.sendRedirect(request, response, AUTHENTICATED_DESTINATION);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        redirectStrategy.sendRedirect(request, response, ACCESS_NOT_PROVISIONED_DESTINATION);
    }
}