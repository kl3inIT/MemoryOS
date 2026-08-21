package io.memoryos.api.security;

import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.identity.IdentityContext;
import io.memoryos.api.invitation.InvitationSessionState;
import io.memoryos.invitation.OrganizationInvitationException;
import io.memoryos.invitation.OrganizationInvitationService;
import io.memoryos.organization.OrganizationId;
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
    private static final String INVITATION_FAILURE_DESTINATION = "/invitation?reason=";

    private final ExternalIdentityResolver identityResolver;
    private final OrganizationAccessResolver organizationAccessResolver;
    private final OrganizationInvitationService invitationService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    ActorSessionAuthenticationSuccessHandler(
            ExternalIdentityResolver identityResolver,
            OrganizationAccessResolver organizationAccessResolver,
            OrganizationInvitationService invitationService
    ) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver must not be null");
        this.organizationAccessResolver = Objects.requireNonNull(
                organizationAccessResolver,
                "organizationAccessResolver must not be null"
        );
        this.invitationService = Objects.requireNonNull(
                invitationService,
                "invitationService must not be null"
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

        var externalIdentity = new ExternalIdentity(issuer.toString(), subject);
        var actorId = identityResolver.resolve(externalIdentity).orElse(null);
        if (actorId == null || !organizationAccessResolver.hasActiveOrganization(actorId)) {
            actorId = acceptInvitation(request, response, oidcUser, externalIdentity);
            if (actorId == null) {
                return;
            }
        }

        var session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(InvitationSessionState.ATTRIBUTE);
        }
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new ActorSessionAuthenticationToken(new IdentityContext(actorId)));
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);
        redirectStrategy.sendRedirect(request, response, AUTHENTICATED_DESTINATION);
    }

    private ActorId acceptInvitation(
            HttpServletRequest request,
            HttpServletResponse response,
            OidcUser oidcUser,
            ExternalIdentity externalIdentity
    ) throws IOException {
        var session = request.getSession(false);
        Object candidate = session == null
                ? null
                : session.getAttribute(InvitationSessionState.ATTRIBUTE);
        if (!(candidate instanceof InvitationSessionState invitationState)) {
            reject(request, response);
            return null;
        }

        try {
            return invitationService.accept(new OrganizationInvitationService.InvitationAcceptance(
                    invitationState.invitationId(),
                    new OrganizationId(invitationState.organizationId()),
                    externalIdentity,
                    oidcUser.getClaimAsString("email"),
                    Boolean.TRUE.equals(oidcUser.getClaimAsBoolean("email_verified"))
            ));
        } catch (OrganizationInvitationException exception) {
            rejectInvitation(request, response, reason(exception.reason()));
            return null;
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        clearSession(request);
        redirectStrategy.sendRedirect(request, response, ACCESS_NOT_PROVISIONED_DESTINATION);
    }

    private void rejectInvitation(
            HttpServletRequest request,
            HttpServletResponse response,
            String reason
    ) throws IOException {
        clearSession(request);
        redirectStrategy.sendRedirect(request, response, INVITATION_FAILURE_DESTINATION + reason);
    }

    private static void clearSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private static String reason(OrganizationInvitationException.Reason reason) {
        return switch (reason) {
            case EMAIL_NOT_VERIFIED -> "email-not-verified";
            case EMAIL_MISMATCH -> "email-mismatch";
            default -> "not-available";
        };
    }
}