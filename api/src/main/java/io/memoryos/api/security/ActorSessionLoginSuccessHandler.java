package io.memoryos.api.security;

import io.memoryos.api.invitation.InvitationSessionState;
import io.memoryos.iam.audit.AuditAction;
import io.memoryos.iam.audit.AuditOutcome;
import io.memoryos.iam.audit.AuditRecord;
import io.memoryos.iam.audit.AuditTrail;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.identity.ActorProfileRecorder;
import io.memoryos.iam.identity.ExternalIdentity;
import io.memoryos.iam.identity.ExternalIdentityResolver;
import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.invitation.InvitationAcceptance;
import io.memoryos.iam.invitation.InvitationException;
import io.memoryos.iam.invitation.InvitationFailureReason;
import io.memoryos.iam.invitation.InvitationService;
import io.memoryos.iam.invitation.VerifiedEmailInvitationAcceptance;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.iam.identity.TrustedIdentityAdmission;
import io.memoryos.iam.identityprovider.JitAdmissionPolicy;
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
    private final ActorProfileRecorder profileRecorder;
    private final TrustedIdentityAdmission trustedIdentityAdmission;
    private final JitAdmissionPolicy jitAdmissionPolicy;
    private final TenantId tenantId;
    private final String trustedIssuer;
    private final AuditTrail audit;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    ActorSessionLoginSuccessHandler(
            ExternalIdentityResolver identityResolver,
            TenantAccessResolver tenantAccessResolver,
            InvitationService invitationService,
            ActorProfileRecorder profileRecorder,
            TrustedIdentityAdmission trustedIdentityAdmission,
            JitAdmissionPolicy jitAdmissionPolicy,
            TenantId tenantId,
            String trustedIssuer,
            AuditTrail audit
    ) {
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.identityResolver = Objects.requireNonNull(identityResolver, "identityResolver must not be null");
        this.tenantAccessResolver = Objects.requireNonNull(
                tenantAccessResolver,
                "tenantAccessResolver must not be null"
        );
        this.invitationService = Objects.requireNonNull(
                invitationService,
                "invitationService must not be null"
        );
        this.profileRecorder = Objects.requireNonNull(profileRecorder, "profileRecorder must not be null");
        this.trustedIdentityAdmission = Objects.requireNonNull(trustedIdentityAdmission);
        this.jitAdmissionPolicy = Objects.requireNonNull(jitAdmissionPolicy);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.trustedIssuer = Objects.requireNonNull(trustedIssuer);
    }

    @Override
    public void onAuthenticationSuccess(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Authentication authentication
    ) throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2Authentication)
                || !(oauth2Authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            refused(null, null, "UNREADABLE_IDENTITY", AuditOutcome.FAILURE);
            rejectLogin(request, response);
            return;
        }

        var idToken = oidcUser.getIdToken();
        var issuer = idToken.getIssuer();
        String subject = idToken.getSubject();
        if (issuer == null || subject == null || subject.isBlank()) {
            refused(null, oidcUser, "UNREADABLE_IDENTITY", AuditOutcome.FAILURE);
            rejectLogin(request, response);
            return;
        }

        var externalIdentity = new ExternalIdentity(issuer.toString(), subject);
        var actorId = identityResolver.resolve(externalIdentity).orElse(null);
        if (actorId == null || !tenantAccessResolver.hasActiveTenant(actorId)) {
            if (trustedIssuer.equals(externalIdentity.issuer())
                    && jitAdmissionPolicy.allows(idToken.getClaims().get("memoryos_identity_provider"))) {
                try {
                    actorId = trustedIdentityAdmission.admit(tenantId, externalIdentity);
                } catch (IamException exception) {
                    if (!IamFailureReason.ACCESS_DENIED.code().equals(exception.code())) {
                        invalidatePartialSession(request);
                        throw exception;
                    }
                    refused(actorId, oidcUser, "NOT_ADMITTED", AuditOutcome.DENIED);
                    rejectLogin(request, response);
                    return;
                } catch (RuntimeException exception) {
                    invalidatePartialSession(request);
                    throw exception;
                }
            } else {
                actorId = acceptInvitation(request, response, oidcUser, externalIdentity);
            }
            if (actorId == null) {
                return;
            }
        }

        try {
            profileRecorder.record(
                    actorId,
                    externalIdentity,
                    oidcUser.getClaimAsString("name"),
                    oidcUser.getClaimAsString("email"),
                    Boolean.TRUE.equals(oidcUser.getClaimAsBoolean("email_verified"))
            );
        } catch (RuntimeException exception) {
            invalidatePartialSession(request);
            throw exception;
        }

        InvitationSessionState.clear(request);
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new ActorAuthenticationToken(new IdentityContext(actorId)));
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);
        ProviderSessionState.remember(request, idToken.getClaimAsString("sid"));
        ActorId signedIn = actorId;
        tenantAccessResolver.findActiveTenant(signedIn).ifPresent(tenant -> audit.recordSeparately(
                AuditRecord.of(AuditAction.LOGIN, tenant).actor(signedIn).build()));
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
            refused(identityResolver.resolve(externalIdentity).orElse(null), oidcUser,
                    continuation != null || activationFlow ? "INVITATION_" + exception.reason().name() : "NOT_ADMITTED",
                    continuation != null || activationFlow ? AuditOutcome.FAILURE : AuditOutcome.DENIED);
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

    /**
     * A sign-in that authenticated at the provider but was not let in. Onyx records the same as
     * {@code auth.login_failure}: refused (not admitted) is {@code DENIED}, an invitation that could not be used is
     * {@code FAILURE}. The person is named by the e-mail their provider asserted, since they may have no profile here.
     */
    private void refused(@org.jspecify.annotations.Nullable ActorId actor,
                         @org.jspecify.annotations.Nullable OidcUser user, String reason, AuditOutcome outcome) {
        String who = user == null ? null : java.util.Objects.requireNonNullElse(user.getClaimAsString("email"),
                user.getSubject());
        audit.recordSeparately(AuditRecord.of(AuditAction.LOGIN_FAILURE, tenantId).outcome(outcome).actor(actor, who)
                .detail("reason", reason).build());
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
            case NOT_OWNER, INVALID_EMAIL, QUERY_INVALID, CONFLICT, NOT_AVAILABLE, IDENTITY_CONFLICT -> "not-available";
        };
    }
}
