package io.memoryos.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditOutcome;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.invitation.InvitationException;
import io.memoryos.iam.invitation.InvitationFailureReason;
import io.memoryos.iam.invitation.InvitationService;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;

class ActorSessionLoginSuccessHandlerTest {

    private static final String ISSUER = "https://keycloak.example/realms/memoryos";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void userInfoCannotSupplyTheTrustedProviderClaimMissingFromTheSignedIdToken() throws Exception {
        assertDenied(ISSUER, null, Map.of("sub", "member", "memoryos_identity_provider", "tasco"), Set.of("tasco"));
    }

    @Test
    void trustedAliasCannotAdmitFromAnotherIssuer() throws Exception {
        assertDenied("https://other.example/realms/memoryos", "tasco", Map.of("sub", "member"), Set.of("tasco"));
    }

    @Test
    void trustedClaimCannotAdmitWhenDeploymentHasNotOptedIn() throws Exception {
        assertDenied(ISSUER, "tasco", Map.of("sub", "member"), Set.of());
    }

    private void assertDenied(String issuer, Object providerClaim, Map<String, Object> userInfo, Set<String> aliases)
            throws Exception {
        var invitations = mock(InvitationService.class);
        when(invitations.acceptVerifiedEmail(any())).thenThrow(new InvitationException(
                InvitationFailureReason.NOT_AVAILABLE, "No eligible invitation"));
        var audit = mock(AuditTrail.class);
        var handler = new ActorSessionLoginSuccessHandler(
                _ -> Optional.empty(),
                mock(TenantAccessResolver.class),
                invitations,
                (_, _, _, _, _) -> { throw new AssertionError("Rejected identity must not record a profile"); },
                (_, _) -> new ActorId(UUID.randomUUID()),
                claim -> claim instanceof String alias && aliases.contains(alias),
                new TenantId(UUID.randomUUID()),
                ISSUER,
                audit
        );
        var claims = new HashMap<String, Object>();
        claims.put("iss", issuer);
        claims.put("sub", "member");
        claims.put("email", "member@example.test");
        claims.put("email_verified", true);
        if (providerClaim != null) {
            claims.put("memoryos_identity_provider", providerClaim);
        }
        var now = Instant.now();
        var user = new DefaultOidcUser(List.of(), new OidcIdToken("synthetic-id-token", now, now.plusSeconds(60), claims),
                new OidcUserInfo(userInfo));
        var request = new MockHttpServletRequest();
        request.getSession();
        var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(request, response, new OAuth2AuthenticationToken(user, List.of(), "memoryos"));
        assertEquals("/access-not-provisioned", response.getRedirectedUrl());
        assertNull(request.getSession(false));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        // The refused sign-in is recorded, naming the person by the e-mail their provider asserted.
        var event = org.mockito.ArgumentCaptor.forClass(AuditRecord.class);
        org.mockito.Mockito.verify(audit).recordSeparately(event.capture());
        assertEquals(AuditAction.LOGIN_FAILURE, event.getValue().action());
        assertEquals(AuditOutcome.DENIED, event.getValue().outcome());
        assertEquals("member@example.test", event.getValue().actorLabel());
        assertEquals("NOT_ADMITTED", event.getValue().details().get("reason"));
    }
}
