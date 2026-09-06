package io.memoryos.iam.keycloak;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.Assert;

@ConfigurationProperties("memoryos.identity.keycloak.admin")
record KeycloakAdminProperties(
        String serverUrl,
        String realm,
        String clientId,
        String clientSecret,
        String actionClientId,
        String actionRedirectUri,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("1s") Duration connectionRequestTimeout,
        @DefaultValue("5s") Duration readTimeout
) {

    private static final Duration MAXIMUM_CLIENT_TIMEOUT = Duration.ofSeconds(30);

    KeycloakAdminProperties {
        Assert.hasText(serverUrl, "memoryos.identity.keycloak.admin.server-url must not be blank");
        Assert.hasText(realm, "memoryos.identity.keycloak.admin.realm must not be blank");
        Assert.hasText(clientId, "memoryos.identity.keycloak.admin.client-id must not be blank");
        Assert.hasText(clientSecret, "memoryos.identity.keycloak.admin.client-secret must not be blank");
        Assert.hasText(actionClientId, "memoryos.identity.keycloak.admin.action-client-id must not be blank");
        Assert.hasText(
                actionRedirectUri,
                "memoryos.identity.keycloak.admin.action-redirect-uri must not be blank"
        );
        requireBoundedTimeout(connectTimeout, "connect-timeout");
        requireBoundedTimeout(connectionRequestTimeout, "connection-request-timeout");
        requireBoundedTimeout(readTimeout, "read-timeout");
    }

    private static void requireBoundedTimeout(Duration value, String property) {
        Objects.requireNonNull(value, "memoryos.identity.keycloak.admin." + property + " must not be null");
        if (value.isZero() || value.isNegative() || value.compareTo(MAXIMUM_CLIENT_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "memoryos.identity.keycloak.admin." + property
                            + " must be greater than zero and at most 30 seconds"
            );
        }
    }
}
