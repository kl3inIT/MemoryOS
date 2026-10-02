package io.memoryos.iam.keycloak;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * MEM-207: the realm's {@code memoryos-mcp-admin} service account, which may change client policies and remove clients.
 * The realm script creates it beside the MCP endpoint; a deployment without the endpoint has no secret.
 */
@ConfigurationProperties("memoryos.identity.keycloak.mcp-admin")
record KeycloakMcpAdminProperties(@DefaultValue("memoryos-mcp-admin") String clientId, @Nullable String clientSecret) {

    boolean configured() {
        return clientSecret != null && !clientSecret.isBlank();
    }
}
