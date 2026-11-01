package io.memoryos.api.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * The browser sign-in. {@code sessionMaxLifetime} is how long a browser session may last after the password that
 * started it, however active it stays; the realm script gives Keycloak's SSO session the same lifetime.
 */
@ConfigurationProperties("memoryos.browser")
record BrowserLoginProperties(String registrationId, Duration sessionMaxLifetime) {

    BrowserLoginProperties {
        Assert.hasText(registrationId, "memoryos.browser.registration-id must not be blank");
        Assert.isTrue(sessionMaxLifetime != null && sessionMaxLifetime.isPositive(),
                "memoryos.browser.session-max-lifetime must be positive");
    }
}
