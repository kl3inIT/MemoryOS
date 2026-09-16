package io.memoryos.mcp;

import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.mcp.persistence.JpaMcpCredentialRepository;
import io.memoryos.mcp.persistence.JpaMcpOAuthClientRepository;
import io.memoryos.mcp.persistence.JpaMcpServerRepository;
import io.memoryos.mcp.persistence.JpaMcpServerToolRepository;
import io.memoryos.mcp.persistence.McpAccessRepository;
import io.memoryos.mcp.persistence.McpCredentialEntity;
import io.memoryos.mcp.persistence.McpServerEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a User may use and their own credentials for it. Reading needs {@code CHAT_WRITE} and access to the server
 * through the organization-wide flag or a Group; a server outside that set is reported as missing. An API key is
 * listed against the server before it is stored, the {@link McpServerService#refreshTools} shape.
 */
@Service
public class McpConnectionService {
    /** A User waits for this dialog, so the probe is shorter than an administrator's explicit refresh. */
    private static final Duration PROBE_DEADLINE = Duration.ofSeconds(30);

    private final JpaMcpServerRepository servers;
    private final JpaMcpServerToolRepository tools;
    private final JpaMcpCredentialRepository credentials;
    private final JpaMcpOAuthClientRepository oauthClients;
    private final McpAccessRepository access;
    private final IamAuthorization authorization;
    private final McpSecrets secrets;
    private final McpClients clients;
    private final McpOAuthService oauth;
    private final TransactionTemplate transactions;

    public McpConnectionService(JpaMcpServerRepository servers, JpaMcpServerToolRepository tools,
                                JpaMcpCredentialRepository credentials, JpaMcpOAuthClientRepository oauthClients,
                                McpAccessRepository access, IamAuthorization authorization, McpSecrets secrets,
                                McpClients clients, McpOAuthService oauth, PlatformTransactionManager transactionManager) {
        this.servers = servers; this.tools = tools; this.credentials = credentials; this.oauthClients = oauthClients;
        this.access = access; this.authorization = authorization; this.secrets = secrets; this.clients = clients;
        this.oauth = oauth;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** An OAuth client a User can choose between when connecting; endpoints stay inside the capability. */
    public record ClientOption(UUID id, String label) {}

    public record ConnectionView(UUID id, String slug, String name, @Nullable String description, String url,
                                 McpAuthType authType, McpAuthPerformer authPerformer, McpServerStatus status,
                                 McpConnectionState state, List<ClientOption> oauthClients, long enabledToolCount,
                                 /** When the User's own credential last changed, including a token refresh. */
                                 @Nullable Instant credentialUpdatedAt, long revision) {}

    /** Servers the User may use, each with their own connection state. */
    @Transactional(readOnly = true)
    public List<ConnectionView> list(ActorId actor) {
        UUID tenant = use(actor);
        Set<UUID> accessible = access.accessibleServerIds(tenant, actor.value());
        Map<UUID, McpCredentialEntity> own = credentials.findByTenantIdAndOwnerActorId(tenant, actor.value()).stream()
                .collect(Collectors.toMap(McpCredentialEntity::serverId, Function.identity()));
        return servers.findByTenantIdOrderByNameAsc(tenant).stream()
                .filter(server -> accessible.contains(server.getId()))
                .map(server -> view(tenant, server, own.get(server.getId())))
                .toList();
    }

    /**
     * Stores the User's own API key for a per-User API-token server after listing the server with it. A rejected
     * key is reported as {@code MCP_AUTHORIZATION_REQUIRED} and nothing is written.
     */
    public ConnectionView saveApiKey(ActorId actor, UUID serverId, String value) {
        String apiKey = McpServerRules.apiKey(value);
        Probe probe = inTransaction(() -> {
            UUID tenant = use(actor);
            var server = accessibleServer(tenant, serverId, actor);
            if (server.authType() != McpAuthType.API_TOKEN || server.authPerformer() != McpAuthPerformer.PER_USER)
                throw McpException.invalid("This server does not take your own API key.");
            Map<String, String> template = server.headerTemplate() == null ? null
                    : McpServerRules.stringMap(secrets.open(tenant, server.getId(), McpSecrets.Purpose.SERVER_HEADERS,
                    Objects.requireNonNull(server.headerTemplate())));
            return new Probe(server.url(), McpServerRules.resolveHeaders(template, McpAuthType.API_TOKEN, apiKey),
                    server.revision());
        });
        try (var session = clients.open(probe.url(), probe.headers(), PROBE_DEADLINE)) {
            session.listTools();
        }
        return inTransaction(() -> {
            UUID tenant = authorization.lockAndRequire(actor, IamCapability.CHAT_WRITE, false).tenantId().value();
            var server = accessibleServer(tenant, serverId, actor);
            if (server.revision() != probe.revision()) throw McpException.conflict();
            Instant now = Instant.now();
            var credential = credentials.findByTenantIdAndServerIdAndOwnerActorId(tenant, serverId, actor.value())
                    .orElseGet(() -> new McpCredentialEntity(UUID.randomUUID(), tenant, serverId, actor.value(), now));
            credential.store(null, secrets.seal(tenant, credential.getId(), McpSecrets.Purpose.CREDENTIAL,
                    McpServerRules.json(Map.of(McpServerRules.API_KEY, apiKey))), null, now);
            credentials.saveAndFlush(credential);
            return view(tenant, server, credential);
        });
    }

    /** Removes the User's own credential; an OAuth connection is revoked at the authorization server. */
    public void disconnect(ActorId actor, UUID serverId) {
        McpAuthType authType = inTransaction(() -> accessibleServer(use(actor), serverId, actor).authType());
        if (authType == McpAuthType.OAUTH) {
            oauth.disconnectUser(actor, serverId);
            return;
        }
        transactions.executeWithoutResult(status -> {
            UUID tenant = authorization.lockAndRequire(actor, IamCapability.CHAT_WRITE, false).tenantId().value();
            accessibleServer(tenant, serverId, actor);
            credentials.findByTenantIdAndServerIdAndOwnerActorId(tenant, serverId, actor.value())
                    .ifPresent(credentials::delete);
            credentials.flush();
        });
    }

    private record Probe(String url, Map<String, String> headers, long revision) {
        @Override public @NonNull String toString() { return "McpProbe[redacted]"; }
    }

    private ConnectionView view(UUID tenant, McpServerEntity server, @Nullable McpCredentialEntity own) {
        McpConnectionState state;
        if (server.authType() == McpAuthType.NONE) state = McpConnectionState.NOT_REQUIRED;
        else if (server.authPerformer() == McpAuthPerformer.ADMIN) state = McpConnectionState.SHARED;
        else if (own == null) state = McpConnectionState.NOT_CONNECTED;
        else state = own.status() == McpCredentialStatus.ACTIVE
                    ? McpConnectionState.CONNECTED : McpConnectionState.REAUTH_REQUIRED;
        List<ClientOption> options = server.authType() == McpAuthType.OAUTH
                && server.authPerformer() == McpAuthPerformer.PER_USER
                ? oauthClients.findByTenantIdAndServerIdOrderByLabelAsc(tenant, server.getId()).stream()
                        .map(client -> new ClientOption(client.getId(), client.label())).toList()
                : List.of();
        return new ConnectionView(server.getId(), server.slug(), server.name(), server.description(), server.url(),
                server.authType(), server.authPerformer(), server.status(), state, options,
                tools.countByTenantIdAndServerIdAndEnabledTrue(tenant, server.getId()),
                own == null ? null : own.updatedAt(), server.revision());
    }

    private McpServerEntity accessibleServer(UUID tenant, UUID serverId, ActorId actor) {
        if (!access.accessible(tenant, serverId, actor.value())) throw McpException.notFound();
        return servers.findByTenantIdAndId(tenant, serverId).orElseThrow(McpException::notFound);
    }

    private UUID use(ActorId actor) {
        return authorization.require(actor, IamCapability.CHAT_WRITE, false).tenantId().value();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }
}
