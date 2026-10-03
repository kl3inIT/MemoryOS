package io.memoryos.iam.keycloak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.memoryos.iam.McpClientGrant;
import io.memoryos.iam.McpClientPolicy;
import io.memoryos.iam.McpClientPolicyException;
import io.memoryos.iam.McpClientPolicyFailureReason;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientPoliciesRepresentation;
import org.keycloak.representations.idm.ClientPolicyRepresentation;
import org.keycloak.representations.idm.ClientProfileRepresentation;
import org.keycloak.representations.idm.ClientProfilesRepresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Keeps the hosts of the {@code memoryos-mcp-cimd} profile (the URIs a document may list) and policy (the host of the
 * document's URL) through the realm admin API, signed in as {@code memoryos-mcp-admin}. The realm script created both
 * and seeded the hosts once; everything else in the realm's client policies is written back as it was read.
 */
@Component
class KeycloakMcpClientPolicy implements McpClientPolicy, DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeycloakMcpClientPolicy.class);
    static final String CIMD = "memoryos-mcp-cimd";
    static final String DOCUMENT_HOSTS = "cimd-allow-permitted-domains";
    static final String CLIENT_ID_HOSTS = "client-id-uri-allow-permitted-domains";

    private final @Nullable Keycloak keycloak;
    private final String realm;

    @Autowired
    KeycloakMcpClientPolicy(KeycloakAdminProperties admin, KeycloakMcpAdminProperties account) {
        this(account.configured()
                ? KeycloakAdminConfiguration.client(admin, account.clientId(), Objects.requireNonNull(account.clientSecret()))
                : null, admin.realm());
    }

    KeycloakMcpClientPolicy(@Nullable Keycloak keycloak, String realm) {
        this.keycloak = keycloak;
        this.realm = Objects.requireNonNull(realm, "realm must not be null");
    }

    @Override
    public boolean configured() {
        return keycloak != null;
    }

    @Override
    public Hosts trusted() {
        return call(() -> {
            var profile = profile(realm().clientPoliciesProfilesResource().getProfiles(false));
            var policy = policy(realm().clientPoliciesPoliciesResource().getPolicies(false));
            return new Hosts(hosts(policy.getConditions().getFirst().getConfiguration(), CLIENT_ID_HOSTS),
                    hosts(profile.getExecutors().getFirst().getConfiguration(), DOCUMENT_HOSTS));
        });
    }

    @Override
    public void trust(Hosts hosts) {
        call(() -> {
            var profiles = realm().clientPoliciesProfilesResource().getProfiles(false);
            var executor = profile(profiles).getExecutors().getFirst();
            if (!hosts(executor.getConfiguration(), DOCUMENT_HOSTS).equals(hosts.documentHosts())) {
                executor.setConfiguration(with(executor.getConfiguration(), DOCUMENT_HOSTS, hosts.documentHosts()));
                realm().clientPoliciesProfilesResource().updateProfiles(profiles);
            }
            var policies = realm().clientPoliciesPoliciesResource().getPolicies(false);
            var condition = policy(policies).getConditions().getFirst();
            if (!hosts(condition.getConfiguration(), CLIENT_ID_HOSTS).equals(hosts.clientIdHosts())) {
                condition.setConfiguration(with(condition.getConfiguration(), CLIENT_ID_HOSTS, hosts.clientIdHosts()));
                realm().clientPoliciesPoliciesResource().updatePolicies(policies);
            }
            return null;
        });
        LOGGER.atInfo().addKeyValue("event", "iam.mcp_client_policy.trusted")
                .addKeyValue("client_id_hosts", hosts.clientIdHosts().size())
                .addKeyValue("document_hosts", hosts.documentHosts().size())
                .log("MCP client policy hosts reconciled");
    }

    @Override
    public int removeClientsOutside(Hosts hosts) {
        int removed = call(() -> {
            int count = 0;
            for (ClientRepresentation client : realm().clients().findAll()) {
                String host = documentHost(client.getClientId());
                if (host == null || hosts.admitsClientIdHost(host)) continue;
                try {
                    realm().clients().get(client.getId()).remove();
                    count++;
                } catch (NotFoundException alreadyGone) {
                    // Removed concurrently: the outcome asked for.
                }
            }
            return count;
        });
        if (removed > 0) {
            LOGGER.atInfo().addKeyValue("event", "iam.mcp_client_policy.clients_removed").addKeyValue("count", removed)
                    .log("MCP clients of untrusted apps removed");
        }
        return removed;
    }

    /** The host of a client ID that is a metadata document's URL; Keycloak's own clients have plain IDs. */
    static @Nullable String documentHost(@Nullable String clientId) {
        if (clientId == null || !clientId.startsWith("https://")) return null;
        return McpClientGrant.Client.host(clientId);
    }

    private RealmResource realm() {
        return Objects.requireNonNull(keycloak, "memoryos-mcp-admin is not configured").realm(realm);
    }

    private static ClientProfileRepresentation profile(ClientProfilesRepresentation profiles) {
        return profiles.getProfiles().stream().filter(profile -> CIMD.equals(profile.getName())).findFirst()
                .orElseThrow(() -> new IllegalStateException("client profile " + CIMD + " is missing"));
    }

    private static ClientPolicyRepresentation policy(ClientPoliciesRepresentation policies) {
        return policies.getPolicies().stream().filter(policy -> CIMD.equals(policy.getName())).findFirst()
                .orElseThrow(() -> new IllegalStateException("client policy " + CIMD + " is missing"));
    }

    private static Set<String> hosts(@Nullable JsonNode configuration, String field) {
        var hosts = new HashSet<String>();
        if (configuration != null) configuration.path(field).forEach(host -> hosts.add(host.asText()));
        return new Hosts(hosts, Set.of()).clientIdHosts();
    }

    private static JsonNode with(@Nullable JsonNode configuration, String field, Set<String> hosts) {
        ObjectNode copy = configuration instanceof ObjectNode object ? object.deepCopy() : JsonNodeFactory.instance.objectNode();
        ArrayNode values = copy.putArray(field);
        hosts.stream().sorted().forEach(values::add);
        return copy;
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ProcessingException | WebApplicationException | IllegalStateException failure) {
            throw new McpClientPolicyException(McpClientPolicyFailureReason.UNAVAILABLE,
                    "Keycloak client policy request failed", failure);
        }
    }

    @Override
    public void destroy() {
        if (keycloak != null) keycloak.close();
    }
}
