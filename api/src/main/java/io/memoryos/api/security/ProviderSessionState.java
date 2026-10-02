package io.memoryos.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.Optional;

/**
 * Keeps the identity-provider session id ({@code sid}) of an admitted browser login in the HTTP session, so sign-out
 * can end that provider session without a provider logout page, and the instant the person last gave their password
 * ({@code auth_time}), which bounds the session's lifetime. Provider tokens are never stored.
 */
final class ProviderSessionState {

    private static final String ATTRIBUTE = ProviderSessionState.class.getName();
    private static final String AUTHENTICATED_AT = ATTRIBUTE + ".authenticatedAt";

    private ProviderSessionState() {
    }

    static void remember(HttpServletRequest request, String providerSessionId, Instant authenticatedAt) {
        var session = request.getSession(true);
        if (providerSessionId != null && !providerSessionId.isBlank()) {
            session.setAttribute(ATTRIBUTE, providerSessionId);
        }
        // Epoch seconds, as the claim carries them; a Long reads back whatever the session serializer.
        session.setAttribute(AUTHENTICATED_AT, authenticatedAt.getEpochSecond());
    }

    static String read(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && session.getAttribute(ATTRIBUTE) instanceof String providerSessionId
                ? providerSessionId
                : null;
    }

    /** Empty for a session signed in before the instant was kept. */
    static Optional<Instant> authenticatedAt(HttpSession session) {
        return session.getAttribute(AUTHENTICATED_AT) instanceof Long seconds
                ? Optional.of(Instant.ofEpochSecond(seconds))
                : Optional.empty();
    }
}
