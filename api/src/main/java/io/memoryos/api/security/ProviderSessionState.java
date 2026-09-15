package io.memoryos.api.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Keeps the identity-provider session id ({@code sid}) of an admitted browser login in the HTTP session, so sign-out
 * can end that provider session without a provider logout page. Provider tokens are never stored.
 */
final class ProviderSessionState {

    private static final String ATTRIBUTE = ProviderSessionState.class.getName();

    private ProviderSessionState() {
    }

    static void remember(HttpServletRequest request, String providerSessionId) {
        if (providerSessionId != null && !providerSessionId.isBlank()) {
            request.getSession(true).setAttribute(ATTRIBUTE, providerSessionId);
        }
    }

    static String read(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && session.getAttribute(ATTRIBUTE) instanceof String providerSessionId
                ? providerSessionId
                : null;
    }
}
