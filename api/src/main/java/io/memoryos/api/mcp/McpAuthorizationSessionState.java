package io.memoryos.api.mcp;

import io.memoryos.iam.identity.IdentityContext;
import io.memoryos.mcp.McpException;
import io.memoryos.mcp.McpOAuthService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** One pending MCP authorization in the browser session; consumed once by the callback. */
public record McpAuthorizationSessionState(UUID actorId, McpOAuthService.Pending pending, String state, String verifier,
                                           Instant expiresAt) implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private static final String ATTRIBUTE = McpAuthorizationSessionState.class.getName();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static void requireSession(HttpServletRequest request, IdentityContext identity) {
        if (request.getSession(false) == null || !actorMatches(request, identity.actorId().value()))
            throw McpException.invalid("MCP authorization requires your existing browser session.");
    }

    public static McpAuthorizationSessionState store(HttpServletRequest request, IdentityContext identity,
                                                     McpOAuthService.Pending pending, String state, String verifier) {
        requireSession(request, identity);
        var stored = new McpAuthorizationSessionState(identity.actorId().value(), pending, state, verifier,
                Instant.now().plusSeconds(600));
        request.getSession(false).setAttribute(ATTRIBUTE, stored);
        return stored;
    }

    public static @Nullable McpAuthorizationSessionState consume(HttpServletRequest request, @Nullable String suppliedState) {
        var session = request.getSession(false);
        if (session == null || suppliedState == null || suppliedState.length() > 128) return null;
        synchronized (session) {
            if (!(session.getAttribute(ATTRIBUTE) instanceof McpAuthorizationSessionState pending)
                    || !MessageDigest.isEqual(pending.state().getBytes(StandardCharsets.UTF_8), suppliedState.getBytes(StandardCharsets.UTF_8)))
                return null;
            session.removeAttribute(ATTRIBUTE);
            return Instant.now().isBefore(pending.expiresAt()) && actorMatches(request, pending.actorId()) ? pending : null;
        }
    }

    public static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static boolean actorMatches(HttpServletRequest request, UUID actorId) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) instanceof SecurityContext context)
                || context.getAuthentication() == null || !context.getAuthentication().isAuthenticated()) return false;
        return context.getAuthentication().getPrincipal() instanceof IdentityContext identity && identity.actorId().value().equals(actorId);
    }

    @Override public @NonNull String toString() { return "McpAuthorizationSessionState[redacted]"; }
}
