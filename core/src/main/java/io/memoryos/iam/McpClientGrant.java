package io.memoryos.iam;

import java.time.Instant;
import java.util.Objects;

/**
 * MEM-114: an outside assistant the member allowed to read MemoryOS through the MCP endpoint, as Keycloak recorded the
 * consent.
 *
 * @param clientId  Keycloak's client ID: {@code memoryos-chatgpt}, or the URL of Claude's client metadata document
 * @param client    which assistant it is, for its name and logo
 * @param name      the name to show: the assistant's, or the host of an unknown metadata document
 * @param grantedAt when the member first allowed it
 */
public record McpClientGrant(String clientId, Client client, String name, Instant grantedAt) {

    public McpClientGrant {
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(grantedAt, "grantedAt must not be null");
    }

    public enum Client { CLAUDE, CHATGPT, OTHER }
}
