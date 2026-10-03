package io.memoryos.iam.keycloak;

import io.memoryos.iam.ExternalIdentity;
import io.memoryos.iam.ExternalIdentityResolver;
import io.memoryos.iam.McpClientGrant;
import io.memoryos.iam.McpClientGrantException;
import io.memoryos.iam.McpClientGrantFailureReason;
import io.memoryos.iam.McpClientGrants;
import io.memoryos.shared.ActorId;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.UserResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads and revokes the member's consents through the realm admin API ({@code GET} and {@code DELETE
 * /admin/realms/{realm}/users/{id}/consents}), authorized by the provisioner service account's {@code manage-users}
 * role. Keycloak's revocation also removes the client's offline session.
 *
 * <p>The member's Keycloak user is the subject of their binding under this realm's issuer. Claude's client ID is the URL
 * of its metadata document, so revocation goes through {@link KeycloakConsentResource}, which sends it as one path
 * segment.
 */
@Component
class KeycloakMcpClientGrants implements McpClientGrants {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeycloakMcpClientGrants.class);

    private final Keycloak keycloak;
    private final String realm;
    private final ExternalIdentityResolver identities;

    KeycloakMcpClientGrants(Keycloak keycloak, KeycloakAdminProperties properties, ExternalIdentityResolver identities) {
        this.keycloak = Objects.requireNonNull(keycloak, "keycloak must not be null");
        this.realm = Objects.requireNonNull(properties, "properties must not be null").realm();
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
    }

    @Override
    public List<McpClientGrant> list(ActorId actor) {
        var grants = new ArrayList<McpClientGrant>();
        for (String user : keycloakUsers(actor)) {
            consents(user).forEach(consent -> grant(consent).ifPresent(grants::add));
        }
        grants.sort(Comparator.comparing(McpClientGrant::grantedAt).reversed());
        return List.copyOf(grants);
    }

    @Override
    public void revoke(ActorId actor, String clientId) {
        Objects.requireNonNull(clientId, "clientId must not be null");
        boolean revoked = false;
        for (String user : keycloakUsers(actor)) {
            boolean granted = consents(user).stream()
                    .flatMap(consent -> grant(consent).stream())
                    .anyMatch(grant -> grant.clientId().equals(clientId));
            if (!granted) continue;
            try {
                keycloak.proxy(KeycloakConsentResource.class).revoke(realm, user, clientId);
            } catch (NotFoundException alreadyGone) {
                // Revoked concurrently: the outcome the member asked for.
            } catch (ProcessingException | WebApplicationException failure) {
                throw unavailable(failure);
            }
            revoked = true;
        }
        if (!revoked) {
            throw new McpClientGrantException(McpClientGrantFailureReason.NOT_FOUND,
                    "The member has no grant to this MCP client");
        }
        LOGGER.atInfo().addKeyValue("event", "iam.mcp_client_grant.revoked")
                .addKeyValue("client", McpClientGrant.Client.of(clientId).name())
                .log("MCP client grant revoked");
    }

    /** The member's Keycloak users: subjects bound under this realm's issuer, never another issuer's. */
    private List<String> keycloakUsers(ActorId actor) {
        String realmPath = "/realms/" + realm;
        return identities.identities(actor).stream()
                .filter(identity -> identity.issuer().endsWith(realmPath))
                .map(ExternalIdentity::subject)
                .toList();
    }

    private List<Map<String, Object>> consents(String user) {
        UserResource resource = keycloak.realm(realm).users().get(user);
        try {
            return resource.getConsents();
        } catch (NotFoundException removed) {
            return List.of(); // The Keycloak user no longer exists, so it holds no grant.
        } catch (ProcessingException | WebApplicationException failure) {
            throw unavailable(failure);
        }
    }

    private static Optional<McpClientGrant> grant(Map<String, Object> consent) {
        if (!(consent.get("clientId") instanceof String clientId)
                || !(consent.get("grantedClientScopes") instanceof List<?> scopes)
                || !scopes.contains(KNOWLEDGE_READ_SCOPE)
                || !(consent.get("createdDate") instanceof Number created)) {
            return Optional.empty();
        }
        return Optional.of(new McpClientGrant(clientId, McpClientGrant.Client.of(clientId),
                McpClientGrant.Client.name(clientId), Instant.ofEpochMilli(created.longValue())));
    }

    private static McpClientGrantException unavailable(Throwable cause) {
        return new McpClientGrantException(McpClientGrantFailureReason.UNAVAILABLE,
                "Keycloak consent request failed", cause);
    }
}
