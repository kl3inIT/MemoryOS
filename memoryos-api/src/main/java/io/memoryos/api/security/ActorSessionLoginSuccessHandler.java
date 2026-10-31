package io.memoryos.api.security;

import io.memoryos.api.invitation.InvitationSessionState;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.IdentityContext;
import io.memoryos.iam.InvitationFailureReason;
import io.memoryos.iam.SignInAdmission;
import io.memoryos.iam.SignInAttempt;
import io.memoryos.iam.SignInOutcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * Reads the validated ID token into a {@link SignInAttempt}, lets IAM's {@link SignInAdmission} decide, and maps the
 * outcome to the browser: an Actor-only session and {@code /}, or an invalidated session and a refusal page.
 */
final class ActorSessionLoginSuccessHandler implements AuthenticationSuccessHandler {

    private static final String AUTHENTICATED_DESTINATION = "/";
    private static final String ACCESS_NOT_PROVISIONED_DESTINATION = "/access-not-provisioned";
    private static final String INVITATION_FAILURE_DESTINATION = "/invitation?reason=";
    private static final String IDENTITY_PROVIDER_CLAIM = "memoryos_identity_provider";

    private final SignInAdmission admission;
    private final SignInAttempt.JitTrust trust;
    private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    ActorSessionLoginSuccessHandler(SignInAdmission admission, SignInAttempt.JitTrust trust) {
        this.admission = Objects.requireNonNull(admission, "admission must not be null");
        this.trust = Objects.requireNonNull(trust, "trust must not be null");
    }

    @Override
    public void onAuthenticationSuccess(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Authentication authentication
    ) throws IOException {
        SignInOutcome outcome;
        try {
            outcome = admission.admit(attempt(request, authentication));
        } catch (RuntimeException exception) {
            invalidatePartialSession(request);
            throw exception;
        }
        switch (outcome) {
            case SignInOutcome.Admitted admitted ->
                    signIn(request, response, admitted, providerSessionId(authentication), authenticatedAt(authentication));
            case SignInOutcome.NotAdmitted ignored -> reject(request, response, ACCESS_NOT_PROVISIONED_DESTINATION);
            case SignInOutcome.InvitationRefused refused ->
                    reject(request, response, INVITATION_FAILURE_DESTINATION + invitationFailurePathReason(refused.reason()));
        }
    }

    private SignInAttempt attempt(HttpServletRequest request, Authentication authentication) {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2Authentication)
                || !(oauth2Authentication.getPrincipal() instanceof OidcUser oidcUser)) {
            return SignInAttempt.unreadable(null, trust);
        }
        var idToken = oidcUser.getIdToken();
        var issuer = idToken.getIssuer();
        String subject = idToken.getSubject();
        String email = oidcUser.getClaimAsString("email");
        if (issuer == null || subject == null || subject.isBlank()) {
            return SignInAttempt.unreadable(asserted(email, oidcUser.getSubject()), trust);
        }
        var continuation = InvitationSessionState.read(request);
        return new SignInAttempt(
                new ExternalIdentity(issuer.toString(), subject),
                asserted(email, oidcUser.getSubject()),
                // Only the signed ID token may select trusted JIT; UserInfo never can.
                idToken.getClaims().get(IDENTITY_PROVIDER_CLAIM),
                displayName(oidcUser),
                email,
                Boolean.TRUE.equals(oidcUser.getClaimAsBoolean("email_verified")),
                continuation == null ? null : new SignInAttempt.Invitation(continuation.invitationId(), continuation.tenant()),
                InvitationSessionState.isActivation(request),
                trust
        );
    }

    private static @Nullable String displayName(OidcUser user) {
        String name = user.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        String givenName = user.getClaimAsString("given_name");
        String familyName = user.getClaimAsString("family_name");
        if (givenName == null || givenName.isBlank()) {
            return familyName == null || familyName.isBlank() ? null : familyName.strip();
        }
        return familyName == null || familyName.isBlank()
                ? givenName.strip()
                : givenName.strip() + " " + familyName.strip();
    }

    private void signIn(
            HttpServletRequest request,
            HttpServletResponse response,
            SignInOutcome.Admitted admitted,
            @Nullable String providerSessionId,
            Instant authenticatedAt
    ) throws IOException {
        InvitationSessionState.clear(request);
        var securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new ActorAuthenticationToken(new IdentityContext(admitted.actorId())));
        SecurityContextHolder.setContext(securityContext);
        securityContextRepository.saveContext(securityContext, request, response);
        ProviderSessionState.remember(request, providerSessionId, authenticatedAt);
        redirectStrategy.sendRedirect(request, response, AUTHENTICATED_DESTINATION);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String destination) throws IOException {
        invalidatePartialSession(request);
        redirectStrategy.sendRedirect(request, response, destination);
    }

    /** The Keycloak user session the ID token names, kept so sign-out can end it. */
    private static @Nullable String providerSessionId(Authentication authentication) {
        return authentication.getPrincipal() instanceof OidcUser user ? user.getIdToken().getClaimAsString("sid") : null;
    }

    /**
     * When the person gave their password: the ID token's {@code auth_time}, which a silent sign-in through a live
     * Keycloak session keeps. Never later than now, so a skewed or wrong claim cannot stretch the session's lifetime;
     * now when the provider sends none.
     */
    private static Instant authenticatedAt(Authentication authentication) {
        Instant now = Instant.now();
        Instant authTime = authentication.getPrincipal() instanceof OidcUser user
                ? user.getIdToken().getAuthenticatedAt()
                : null;
        return authTime == null || authTime.isAfter(now) ? now : authTime;
    }

    private static @Nullable String asserted(@Nullable String email, @Nullable String subject) {
        return email != null ? email : subject;
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
