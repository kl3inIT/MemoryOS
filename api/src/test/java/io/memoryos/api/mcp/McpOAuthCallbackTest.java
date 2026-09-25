package io.memoryos.api.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.memoryos.api.mcp.contract.McpOAuthAuthorizationRequest;
import io.memoryos.api.security.ActorAuthenticationToken;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IdentityContext;
import io.memoryos.mcp.McpException;
import io.memoryos.mcp.McpOAuthService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** Session-bound start and callback, as {@code GoogleDriveOAuthTest} covers the MEM-60 flow. */
class McpOAuthCallbackTest {
    private final McpOAuthService oauth = mock(McpOAuthService.class);
    private final McpOAuthController controller = new McpOAuthController(oauth);
    private final McpOAuthCallbackController callback = new McpOAuthCallbackController(oauth);
    private final IdentityContext identity = new IdentityContext(new ActorId(UUID.randomUUID()));
    private final McpOAuthService.Pending pending =
            new McpOAuthService.Pending(UUID.randomUUID(), UUID.randomUUID(), 3, UUID.randomUUID(), 2, null, "/admin/mcp");
    private MockHttpSession session;

    @BeforeEach
    void authenticatedSession() {
        session = new MockHttpSession();
        authenticate(identity);
    }

    @Test
    void startRequiresTheActorsBrowserSessionAndBindsStateAndVerifierToIt() throws IOException {
        var withoutSession = new MockHttpServletRequest();
        assertEquals("MCP_INVALID", assertThrows(McpException.class, () -> controller.authorize(identity, pending.serverId(),
                new McpOAuthAuthorizationRequest(pending.oauthClientId()), withoutSession)).code());
        verifyNoInteractions(oauth);

        when(oauth.startAdministratorAuthorization(eq(identity.actorId()), eq(pending.serverId()), eq(pending.oauthClientId()),
                anyString(), anyString())).thenAnswer(call -> new McpOAuthService.AuthorizationStart(URI.create(
                "https://as.example/authorize?state=" + call.getArgument(3) + "&code_challenge=" + call.getArgument(4)), pending));
        var response = controller.authorize(identity, pending.serverId(), new McpOAuthAuthorizationRequest(pending.oauthClientId()), request());

        assertEquals("no-store", response.getHeaders().getCacheControl());
        var stored = (McpAuthorizationSessionState) session.getAttribute(McpAuthorizationSessionState.class.getName());
        assertEquals(pending, stored.pending());
        assertEquals(identity.actorId().value(), stored.actorId());
        String url = response.getBody().authorizationUrl();
        assertTrue(url.contains("state=" + stored.state()));
        assertTrue(url.contains("code_challenge=" + McpAuthorizationSessionState.challenge(stored.verifier())));
        assertFalse(url.contains(stored.verifier()));
        assertFalse(stored.toString().contains(stored.verifier()));
        var bytes = new ByteArrayOutputStream();
        try (var serialized = new ObjectOutputStream(bytes)) { serialized.writeObject(stored); }
        assertTrue(bytes.size() > 0);
    }

    @Test
    void callbackCompletesOnceWithTheStoredVerifierAndIssuer() throws IOException {
        McpAuthorizationSessionState.store(request(), identity, pending, "the-state", "the-verifier");

        var response = deliver("state=the-state", "code=the-code", "iss=https://as.example");

        assertEquals("/admin/mcp?mcp=connected&serverId=" + pending.serverId(), response.getRedirectedUrl());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        verify(oauth).complete(identity.actorId(), pending, "the-code", "the-verifier", "https://as.example");
        assertEquals("/?mcp=authorization-failed", deliver("state=the-state", "code=the-code").getRedirectedUrl());
        verify(oauth, times(1)).complete(any(), any(), any(), any(), any());
    }

    @Test
    void callbackReturnsAConnectingUserToTheOriginatingChat() throws IOException {
        var userPending = new McpOAuthService.Pending(pending.tenantId(), pending.serverId(), 3, pending.oauthClientId(), 2,
                identity.actorId().value(), "/chat/session-7");
        McpAuthorizationSessionState.store(request(), identity, userPending, "u1", "u-verifier");

        var response = deliver("state=u1", "code=u-code");

        assertEquals("/chat/session-7?mcp=connected&serverId=" + userPending.serverId(), response.getRedirectedUrl());
        verify(oauth).complete(identity.actorId(), userPending, "u-code", "u-verifier", null);
    }

    @Test
    void callbackMapsFailuresToOutcomesWithoutUpstreamDetail() throws IOException {
        doThrow(McpException.oauthIssuerMismatch()).when(oauth).complete(any(), any(), any(), any(), any());
        McpAuthorizationSessionState.store(request(), identity, pending, "s1", "v");
        assertEquals("/admin/mcp?mcp=issuer-mismatch&serverId=" + pending.serverId(), deliver("state=s1", "code=c").getRedirectedUrl());

        reset(oauth);
        doThrow(McpException.conflict()).when(oauth).complete(any(), any(), any(), any(), any());
        McpAuthorizationSessionState.store(request(), identity, pending, "s2", "v");
        assertEquals("/admin/mcp?mcp=configuration-changed&serverId=" + pending.serverId(), deliver("state=s2", "code=c").getRedirectedUrl());

        reset(oauth);
        doThrow(new IllegalStateException("upstream body with secret")).when(oauth)
                .complete(any(), any(), any(), any(), any());
        McpAuthorizationSessionState.store(request(), identity, pending, "s3", "v");
        var failed = deliver("state=s3", "code=c");
        assertEquals("/admin/mcp?mcp=authorization-failed&serverId=" + pending.serverId(), failed.getRedirectedUrl());
        assertFalse(failed.getRedirectedUrl().contains("secret"));

        reset(oauth);
        McpAuthorizationSessionState.store(request(), identity, pending, "s4", "v");
        assertEquals("/admin/mcp?mcp=authorization-cancelled&serverId=" + pending.serverId(),
                deliver("state=s4", "error=access_denied").getRedirectedUrl());
        McpAuthorizationSessionState.store(request(), identity, pending, "s5", "v");
        assertEquals("/admin/mcp?mcp=authorization-failed&serverId=" + pending.serverId(),
                deliver("state=s5", "code=c", "iss=https://a.example", "iss=https://b.example").getRedirectedUrl());
        verify(oauth, never()).complete(any(), any(), any(), any(), any());
    }

    @Test
    void callbackRejectsUnknownStateExpiredSessionsAndAnotherActor() throws IOException {
        McpAuthorizationSessionState.store(request(), identity, pending, "the-state", "v");
        assertEquals("/?mcp=authorization-failed", deliver("state=other-state", "code=c").getRedirectedUrl());

        authenticate(new IdentityContext(new ActorId(UUID.randomUUID())));
        assertEquals("/?mcp=authorization-failed", deliver("state=the-state", "code=c").getRedirectedUrl());

        var noSession = new MockHttpServletRequest();
        noSession.addParameter("state", "the-state");
        noSession.addParameter("code", "c");
        var response = new MockHttpServletResponse();
        callback.callback(noSession, response);
        assertEquals("/?mcp=authorization-failed", response.getRedirectedUrl());
        verify(oauth, never()).complete(any(), any(), any(), any(), any());
        assertTrue(new String(response.getContentAsByteArray(), StandardCharsets.UTF_8).isEmpty());
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }

    private MockHttpServletResponse deliver(String... parameters) throws IOException {
        var request = request();
        for (String parameter : parameters) {
            int separator = parameter.indexOf('=');
            request.addParameter(parameter.substring(0, separator), parameter.substring(separator + 1));
        }
        var response = new MockHttpServletResponse();
        callback.callback(request, response);
        return response;
    }

    private void authenticate(IdentityContext principal) {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ActorAuthenticationToken(principal));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    }
}
