package io.memoryos.api.security;

import io.memoryos.api.invitation.InvitationSessionState;
import io.memoryos.identity.ActorId;
import io.memoryos.identity.ExternalIdentity;
import io.memoryos.identity.ExternalIdentityResolver;
import io.memoryos.identity.IdentityContext;
import io.memoryos.invitation.InvitationAcceptance;
import io.memoryos.invitation.InvitationException;
import io.memoryos.invitation.InvitationFailureReason;
import io.memoryos.invitation.InvitationService;
import io.memoryos.invitation.VerifiedEmailInvitationAcceptance;
import io.memoryos.tenant.TenantAccessResolver;
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

final class ActorSessionLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final String AUTHENTICATED_DESTINATION = "/";
    private static final String ACCESS_NOT_PROVISIONED_DESTINATION = "/access-not-provisioned";
    private static final String INVITATION_FAILURE_DESTINATION = "/invitation?reason=";

    private final ExternalIdentityResolver identityResolver;
    private final TenantAccessResolver tenantAccessResolver;
    private final InvitationService invitationService;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    ActorSessionLoginSuccessHandler(
            ExternalIdentityResolver identityResolver,
            TenantAccessResolver tenantAccessResolver,
            InvitationService invitationService
    ) {
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver must not be null");
        this.tenantAccessResolver = Objects.requireNonNull(
                tenantAccessResolver,
                "tenantAccessResolver must not be null"
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
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2Authentication)
                || !(oauth2Authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            rejectLogin(request, response);
            return;
        }

        var issuer = oidcUser.getIssuer();
        String subject = oidcUser.getSubject();
        if (issuer == null || subject == null || subject.isBlank()) {
            rejectLogin(request, response);
            return;
        }

        var externalIdentity = new ExternalIdentity(issuer.toString(), subject);
        var actorId = identityResolver.resolve(externalIdentity).orElse(null);
        if (actorId == null || !tenantAccessResolver.hasActiveTenant(actorId)) {
            actorId = acceptInvitation(request, response, oidcUser, externalIdentity);
            if (actorId == null) {
                return;
            }
        }

        InvitationSessionState.clear(request);
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new ActorAuthenticationToken(new IdentityContext(actorId)));
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
        var continuation = InvitationSessionState.read(request);
        boolean activationFlow = InvitationSessionState.isActivation(request);

        try {
            if (continuation != null) {
                return invitationService.accept(new InvitationAcceptance(
                        continuation.invitationId(),
                        continuation.tenant(),
                        externalIdentity,
                        oidcUser.getClaimAsString("email"),
                        Boolean.TRUE.equals(oidcUser.getClaimAsBoolean("email_verified"))
                ));
            }
            return invitationService.acceptVerifiedEmail(
                    new VerifiedEmailInvitationAcceptance(
                            externalIdentity,
                            oidcUser.getClaimAsString("email"),
                            Boolean.TRUE.equals(oidcUser.getClaimAsBoolean("email_verified"))
                    )
            );
        } catch (InvitationException exception) {
            if (continuation != null || activationFlow) {
                rejectInvitation(
                        request,
                        response,
                        invitationFailurePathReason(exception.reason())
                );
            } else {
                rejectLogin(request, response);
            }
            return null;
        }
    }

    private void rejectLogin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        invalidatePartialSession(request);
        redirectStrategy.sendRedirect(request, response, ACCESS_NOT_PROVISIONED_DESTINATION);
    }

    private void rejectInvitation(
            HttpServletRequest request,
            HttpServletResponse response,
            String reason
    ) throws IOException {
        invalidatePartialSession(request);
        redirectStrategy.sendRedirect(request, response, INVITATION_FAILURE_DESTINATION + reason);
    }

    private static void invalidatePartialSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    private static String invitationFailurePathReason(InvitationFailureReason reason) {
        return switch (reason) {
            case EMAIL_NOT_VERIFIED -> "email-not-verified";
            case EMAIL_MISMATCH -> "email-mismatch";
            case NOT_OWNER, INVALID_EMAIL, CONFLICT, NOT_AVAILABLE, IDENTITY_CONFLICT -> "not-available";
        };
    }
}
