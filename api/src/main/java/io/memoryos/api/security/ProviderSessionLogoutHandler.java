package io.memoryos.api.security;

import io.memoryos.iam.identity.ProviderSessionTerminator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Objects;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutHandler;

/**
 * Runs before the HTTP session is invalidated: ends the identity-provider session recorded at login and remembers
 * whether that succeeded, so {@link SessionLogoutSuccessHandler} only returns a provider logout page when it did not.
 * A provider failure never prevents the local sign-out.
 */
final class ProviderSessionLogoutHandler implements LogoutHandler {

    private static final String ENDED_ATTRIBUTE = ProviderSessionLogoutHandler.class.getName() + ".ENDED";

    private final ProviderSessionTerminator terminator;

    ProviderSessionLogoutHandler(ProviderSessionTerminator terminator) {
        this.terminator = Objects.requireNonNull(terminator, "terminator must not be null");
    }

    @Override
    public void logout(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @Nullable Authentication authentication
    ) {
        String providerSessionId = ProviderSessionState.read(request);
        boolean ended = false;
        if (providerSessionId != null) {
            try {
                ended = terminator.end(providerSessionId);
            } catch (RuntimeException exception) {
                ended = false;
            }
        }
        request.setAttribute(ENDED_ATTRIBUTE, ended);
    }

    static boolean providerSessionEnded(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(ENDED_ATTRIBUTE));
    }
}
