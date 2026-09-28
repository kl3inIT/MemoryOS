package io.memoryos.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.api.invitation.InvitationSessionState;
import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.IdentityContext;
import io.memoryos.iam.InvitationFailureReason;
import io.memoryos.iam.SignInAttempt;
import io.memoryos.iam.SignInOutcome;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** The callback's side of sign-in: what it reads from the provider and how it maps IAM's decision to the browser. */
class ActorSessionLoginSuccessHandlerTest {

    private static final String ISSUER = "https://keycloak.example/realms/memoryos";
    private static final SignInAttempt.JitTrust TRUST = new SignInAttempt.JitTrust(ISSUER, new TenantId(UUID.randomUUID()));
    private final AtomicReference<SignInAttempt> seen = new AtomicReference<>();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userInfoCannotSupplyTheTrustedProviderClaimMissingFromTheSignedIdToken() throws Exception {
        var request = sessionRequest();
        var response = new MockHttpServletResponse();
        handler(new SignInOutcome.NotAdmitted()).onAuthenticationSuccess(request, response,
                login(Map.of(), Map.of("sub", "member", "memoryos_identity_provider", "tasco")));

        assertNull(seen.get().identityProviderClaim());
        assertEquals(new ExternalIdentity(ISSUER, "member"), seen.get().identity());
        assertEquals("member@example.test", seen.get().assertedLabel());
        assertEquals("/access-not-provisioned", response.getRedirectedUrl());
        assertNull(request.getSession(false));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void passesTheSignedClaimAndInvitationContinuationAndSignsInTheAdmittedActorOnly() throws Exception {
        var actor = new ActorId(UUID.randomUUID());
        var request = sessionRequest();
        var invitation = new InvitationSessionState(UUID.randomUUID(), UUID.randomUUID());
        invitation.store(request);
        var response = new MockHttpServletResponse();
        handler(new SignInOutcome.Admitted(actor)).onAuthenticationSuccess(request, response,
                login(Map.of("memoryos_identity_provider", "tasco", "sid", "provider-session"), Map.of("sub", "member")));

        assertEquals("tasco", seen.get().identityProviderClaim());
        assertEquals(new SignInAttempt.Invitation(invitation.invitationId(), invitation.tenant()), seen.get().invitation());
        assertEquals(TRUST, seen.get().trust());
        assertEquals("/", response.getRedirectedUrl());
        var session = request.getSession(false);
        assertNotNull(session);
        assertNull(InvitationSessionState.read(request));
        var saved = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertEquals(new IdentityContext(actor), saved.getAuthentication().getPrincipal());
    }

    @Test
    void anUnusableInvitationReturnsToTheInvitationPageWithItsSafeReason() throws Exception {
        var request = sessionRequest();
        var response = new MockHttpServletResponse();
        handler(new SignInOutcome.InvitationRefused(InvitationFailureReason.EMAIL_MISMATCH))
                .onAuthenticationSuccess(request, response, login(Map.of(), Map.of("sub", "member")));

        assertEquals("/invitation?reason=email-mismatch", response.getRedirectedUrl());
        assertNull(request.getSession(false));
    }

    @Test
    void aPrincipalWithoutAnIdTokenIsUnreadableAndAFailedDecisionEndsThePartialSession() throws Exception {
        var request = sessionRequest();
        var response = new MockHttpServletResponse();
        handler(new SignInOutcome.NotAdmitted()).onAuthenticationSuccess(request, response,
                new TestingAuthenticationToken("someone", "n/a"));
        assertNull(seen.get().identity());
        assertNull(seen.get().assertedLabel());
        assertEquals("/access-not-provisioned", response.getRedirectedUrl());

        var failing = sessionRequest();
        var handler = new ActorSessionLoginSuccessHandler(_ -> { throw new IllegalStateException("database unavailable"); }, TRUST);
        assertThrows(IllegalStateException.class, () -> handler.onAuthenticationSuccess(failing, new MockHttpServletResponse(),
                login(Map.of(), Map.of("sub", "member"))));
        assertNull(failing.getSession(false));
    }

    private ActorSessionLoginSuccessHandler handler(SignInOutcome outcome) {
        return new ActorSessionLoginSuccessHandler(attempt -> {
            seen.set(attempt);
            return outcome;
        }, TRUST);
    }

    private static MockHttpServletRequest sessionRequest() {
        var request = new MockHttpServletRequest();
        request.getSession();
        return request;
    }

    private static OAuth2AuthenticationToken login(Map<String, Object> idTokenClaims, Map<String, Object> userInfo) {
        var claims = new HashMap<String, Object>(idTokenClaims);
        claims.put("iss", ISSUER);
        claims.put("sub", "member");
        claims.put("email", "member@example.test");
        claims.put("email_verified", true);
        var now = Instant.now();
        var user = new DefaultOidcUser(List.of(), new OidcIdToken("synthetic-id-token", now, now.plusSeconds(60), claims),
                new OidcUserInfo(userInfo));
        return new OAuth2AuthenticationToken(user, List.of(), "memoryos");
    }
}
