package io.memoryos.iam;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * MEM-114: an outside assistant the member allowed to read MemoryOS through the MCP endpoint, as Keycloak recorded the
 * consent.
 *
 * @param clientId  Keycloak's client ID, the URL of the assistant's client metadata document
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

    /** Which assistant a client is, by the host of its metadata document's URL; anything else is {@code OTHER}. */
    public enum Client {
        CLAUDE(List.of("claude.ai", "claude.com")),
        CHATGPT(List.of("chatgpt.com")),
        OTHER(List.of());

        private final List<String> hosts;

        Client(List<String> hosts) {
            this.hosts = hosts;
        }

        public static Client of(String clientId) {
            String host = host(clientId);
            if (host == null) return OTHER;
            for (Client client : values()) {
                if (client.hosts.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain))) {
                    return client;
                }
            }
            return OTHER;
        }

        /** The name to show: the assistant's, or the host of an unknown metadata document. */
        public static String name(String clientId) {
            return switch (of(clientId)) {
                case CLAUDE -> "Claude";
                case CHATGPT -> "ChatGPT";
                case OTHER -> Optional.ofNullable(host(clientId)).orElse(clientId);
            };
        }

        /** The lower-case host of a metadata document's URL, or null for a client ID that is not one. */
        public static @Nullable String host(String clientId) {
            try {
                String host = URI.create(clientId).getHost();
                return host == null ? null : host.toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException notAUri) {
                return null;
            }
        }
    }
}
