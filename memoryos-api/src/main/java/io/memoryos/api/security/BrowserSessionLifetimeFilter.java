package io.memoryos.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends a browser session once its password is older than {@code memoryos.browser.session-max-lifetime}, however active
 * the session stays: a tab that polls would otherwise keep it forever. It runs after Spring Session restores the
 * session and before Spring Security reads the sign-in from it, so the request continues signed out and the browser
 * signs in again through Keycloak, whose SSO session ends at the same age. A session from before the password was
 * kept is measured from its creation.
 */
final class BrowserSessionLifetimeFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(BrowserSessionLifetimeFilter.class);

    private final Duration maxLifetime;

    BrowserSessionLifetimeFilter(Duration maxLifetime) {
        this.maxLifetime = maxLifetime;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var session = request.getSession(false);
        if (session != null) {
            Instant created = Instant.ofEpochMilli(session.getCreationTime());
            // A sign-in never ends later than the session that holds it began plus the lifetime.
            Instant since = ProviderSessionState.authenticatedAt(session)
                    .filter(authenticated -> authenticated.isBefore(created))
                    .orElse(created);
            if (!Instant.now().isBefore(since.plus(maxLifetime))) {
                session.invalidate();
                LOGGER.atInfo().addKeyValue("event", "identity.browser_session.expired")
                        .log("Browser session ended at its maximum lifetime");
            }
        }
        chain.doFilter(request, response);
    }
}
