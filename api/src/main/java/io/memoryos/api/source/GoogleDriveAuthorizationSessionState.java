package io.memoryos.api.source;

import io.memoryos.connector.GoogleDriveAuthorizationService.Preparation;
import io.memoryos.connector.CredentialId;
import io.memoryos.identity.IdentityContext;
import io.memoryos.tenant.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

public record GoogleDriveAuthorizationSessionState(UUID actorId, UUID tenantId, String name,
        @Nullable UUID credentialId, @Nullable Long expectedRevision, String state, String verifier,
        String nonce, Instant expiresAt, UUID consentId, String oauthClientSnapshot) implements Serializable {
    @Serial private static final long serialVersionUID = 3L;
    private static final String ATTRIBUTE = GoogleDriveAuthorizationSessionState.class.getName();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static GoogleDriveAuthorizationSessionState start(HttpServletRequest request, IdentityContext identity, Preparation preparation) {
        var session = request.getSession(false);
        if (session == null || !actorMatches(request, identity.actorId().value())) {
            throw io.memoryos.connector.SourceException.invalid("Google authorization requires your existing browser session.", "OAuth requires matching Actor session");
        }
        var pending = new GoogleDriveAuthorizationSessionState(identity.actorId().value(), preparation.tenantId().value(),
                preparation.name(), preparation.credentialId() == null ? null : preparation.credentialId().value(),
                preparation.expectedRevision(), random(), random(), random(), Instant.now().plusSeconds(600),
                preparation.consentId(), preparation.oauthClientSnapshot());
        session.setAttribute(ATTRIBUTE, pending);
        return pending;
    }

    public static @Nullable GoogleDriveAuthorizationSessionState consume(HttpServletRequest request, @Nullable String suppliedState) {
        var session = request.getSession(false);
        if (session == null || suppliedState == null || suppliedState.length() > 128) return null;
        synchronized (session) {
            if (!(session.getAttribute(ATTRIBUTE) instanceof GoogleDriveAuthorizationSessionState pending)
                    || !equal(pending.state(), suppliedState)) return null;
            session.removeAttribute(ATTRIBUTE);
            return Instant.now().isBefore(pending.expiresAt()) && actorMatches(request, pending.actorId()) ? pending : null;
        }
    }

    public Preparation preparation() {
        return new Preparation(new TenantId(tenantId), name, credentialId == null ? null : new CredentialId(credentialId), expectedRevision,
                consentId, oauthClientSnapshot);
    }

    public String challenge() {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    static boolean equal(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean actorMatches(HttpServletRequest request, UUID actorId) {
        var session = request.getSession(false);
        if (session == null || !(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) instanceof SecurityContext context)
                || context.getAuthentication() == null || !context.getAuthentication().isAuthenticated()) return false;
        return context.getAuthentication().getPrincipal() instanceof IdentityContext identity && identity.actorId().value().equals(actorId);
    }

    private static String random() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override public String toString() { return "GoogleDriveAuthorizationSessionState[redacted]"; }
}
