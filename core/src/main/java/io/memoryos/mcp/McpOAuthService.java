package io.memoryos.mcp;

import io.memoryos.shared.TenantId;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.mcp.persistence.JpaMcpCredentialRepository;
import io.memoryos.mcp.persistence.JpaMcpOAuthClientRepository;
import io.memoryos.mcp.persistence.JpaMcpServerRepository;
import io.memoryos.mcp.persistence.McpAccessRepository;
import io.memoryos.mcp.persistence.McpCredentialEntity;
import io.memoryos.mcp.persistence.McpOAuthClientEntity;
import io.memoryos.mcp.persistence.McpServerEntity;
import java.io.Serial;
import java.io.Serializable;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OAuth clients, administrator connections and access-token refresh for MCP servers. Every authorization-server
 * call runs between transactions; writes after a call are fenced by the revisions read before it.
 */
@Service
public class McpOAuthService {
    static final String CLIENT_NAME = "MemoryOS";
    /** Where an administrator's own connection returns; Users carry their originating page instead. */
    static final String ADMINISTRATION_PATH = "/admin/mcp";
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);
    private static final int MAX_CLIENTS = 32;
    private static final int MAX_SECRET_CHARACTERS = 16384;
    private static final String ACCESS_TOKEN = "access_token";
    private static final String REFRESH_TOKEN = "refresh_token";

    private final JpaMcpServerRepository servers;
    private final JpaMcpOAuthClientRepository clients;
    private final JpaMcpCredentialRepository credentials;
    private final McpAccessRepository access;
    private final IamAuthorization authorization;
    private final McpSecrets secrets;
    private final McpOAuthProtocol protocol;
    private final McpOAuthProperties properties;
    private final TransactionTemplate transactions;
    private final AuditTrail audit;

    public McpOAuthService(JpaMcpServerRepository servers, JpaMcpOAuthClientRepository clients,
                           JpaMcpCredentialRepository credentials, McpAccessRepository access,
                           IamAuthorization authorization, McpSecrets secrets,
                           McpOAuthProtocol protocol, McpOAuthProperties properties,
                           PlatformTransactionManager transactionManager, AuditTrail audit) {
        this.audit = audit;
        this.servers = servers; this.clients = clients; this.credentials = credentials; this.access = access;
        this.authorization = authorization;
        this.secrets = secrets; this.protocol = protocol; this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record ClientView(UUID id, String label, McpOAuthClientSource source, String issuer, String clientId,
                             boolean clientSecretConfigured, McpTokenEndpointAuthMethod tokenEndpointAuthMethod,
                             String authorizationEndpoint, String tokenEndpoint, @Nullable String revocationEndpoint,
                             boolean issParameterRequired, long revision) {}

    /** A pre-registered client entered by the administrator. */
    public record ClientInput(String label, String issuer, String clientId, McpServerService.SecretChange clientSecret,
                              McpTokenEndpointAuthMethod tokenEndpointAuthMethod, String authorizationEndpoint,
                              String tokenEndpoint, @Nullable String revocationEndpoint, boolean issParameterRequired) {
        @Override public @NonNull String toString() { return "McpOAuthClientInput[redacted]"; }
    }

    public record AuthorizationServerView(String issuer, String authorizationEndpoint, String tokenEndpoint,
                                          @Nullable String registrationEndpoint, @Nullable String revocationEndpoint,
                                          boolean issParameterSupported, boolean registrationAvailable,
                                          boolean metadataDocumentAvailable) {}

    public record DiscoveryView(String resource, List<String> suggestedScopes, List<AuthorizationServerView> authorizationServers) {}

    /**
     * Session-bound continuation of an authorization; holds identifiers and revisions only. {@code ownerActorId} is
     * null for the shared administrator connection and otherwise names the connecting User.
     */
    public record Pending(UUID tenantId, UUID serverId, long serverRevision, UUID oauthClientId, long oauthClientRevision,
                          @Nullable UUID ownerActorId, String returnPath) implements Serializable {
        @Serial private static final long serialVersionUID = 2L;
    }

    public record AuthorizationStart(URI authorizationUrl, Pending pending) {}

    private record Exchange(URI tokenEndpoint, McpOAuthProtocol.Client client, String resource) {
        @Override public @NonNull String toString() { return "McpOAuthExchange[redacted]"; }
    }

    private record TokenSnapshot(UUID credentialId, long revision, @Nullable UUID ownerActorId, Map<String, String> payload,
                                 @Nullable Instant expiresAt, URI tokenEndpoint, McpOAuthProtocol.Client client, String resource) {
        @Override public @NonNull String toString() { return "McpOAuthTokenSnapshot[redacted]"; }
    }

    private record Revocation(URI endpoint, McpOAuthProtocol.Client client, String token) {
        @Override public @NonNull String toString() { return "McpOAuthRevocation[redacted]"; }
    }

    /** Discovery for administrator review; nothing is persisted. */
    public DiscoveryView discover(ActorId actor, UUID serverId) {
        String url = inTransaction(() -> {
            var server = oauthServer(read(actor), serverId);
            if (server.oauthProviderMode() != McpOAuthProviderMode.AUTO_DISCOVERY)
                throw McpException.invalid("Known-provider servers use the endpoints the administrator entered.");
            return server.url();
        });
        var discovery = protocol.discover(url);
        String challenged = discovery.challengedScope();
        List<String> suggested = challenged != null && !challenged.isBlank()
                ? List.of(challenged.trim().split("\\s+")) : discovery.protectedResource().scopesSupported();
        URI metadataDocument = metadataDocumentOrNull();
        return new DiscoveryView(discovery.protectedResource().resource(), suggested,
                discovery.authorizationServers().stream().map(server -> new AuthorizationServerView(server.issuer(),
                        server.authorizationEndpoint().toString(), server.tokenEndpoint().toString(),
                        server.registrationEndpoint() == null ? null : server.registrationEndpoint().toString(),
                        server.revocationEndpoint() == null ? null : server.revocationEndpoint().toString(),
                        server.issParameterSupported(), server.registrationEndpoint() != null,
                        server.clientIdMetadataDocumentSupported() && metadataDocument != null)).toList());
    }

    @Transactional(readOnly = true)
    public List<ClientView> clients(ActorId actor, UUID serverId) {
        UUID tenant = read(actor);
        oauthServer(tenant, serverId);
        return clients.findByTenantIdAndServerIdOrderByLabelAsc(tenant, serverId).stream().map(McpOAuthService::view).toList();
    }

    @Transactional
    public ClientView createClient(ActorId actor, UUID serverId, ClientInput input) {
        UUID tenant = write(actor);
        oauthServer(tenant, serverId);
        requireCapacity(tenant, serverId);
        Instant now = Instant.now();
        var client = new McpOAuthClientEntity(UUID.randomUUID(), tenant, serverId, McpOAuthClientSource.ADMIN, now);
        applyAdminInput(tenant, client, input, null, now);
        var saved = view(clients.saveAndFlush(client));
        clientChange(tenant, actor, serverId, "CREATE", saved);
        return saved;
    }

    @Transactional
    public ClientView updateClient(ActorId actor, UUID serverId, UUID clientId, long revision, ClientInput input) {
        UUID tenant = write(actor);
        oauthServer(tenant, serverId);
        var client = clients.findByTenantIdAndServerIdAndId(tenant, serverId, clientId).orElseThrow(McpException::notFound);
        if (client.revision() != revision) throw McpException.conflict();
        if (client.source() != McpOAuthClientSource.ADMIN)
            throw McpException.invalid("Registered clients cannot be edited; delete and register again.");
        applyAdminInput(tenant, client, input, client.clientSecret(), Instant.now());
        var saved = view(clients.saveAndFlush(client));
        clientChange(tenant, actor, serverId, "UPDATE", saved);
        return saved;
    }

    /** Registers MemoryOS with a discovered authorization server by DCR, or uses its Client ID Metadata Document. */
    public ClientView createDiscoveredClient(ActorId actor, UUID serverId, @Nullable String label, @Nullable String issuer,
                                             McpOAuthClientSource source) {
        if (source == null || source == McpOAuthClientSource.ADMIN)
            throw McpException.invalid("Choose client registration or the client metadata document.");
        String validLabel = McpServerRules.text(label, 100, "label");
        String url = inTransaction(() -> {
            UUID tenant = read(actor);
            var server = oauthServer(tenant, serverId);
            if (server.oauthProviderMode() != McpOAuthProviderMode.AUTO_DISCOVERY)
                throw McpException.invalid("Known-provider servers use pre-registered clients.");
            if (!secrets.configured()) throw McpException.notConfigured();
            requireLabelAvailable(tenant, serverId, validLabel, null);
            return server.url();
        });
        var authorizationServer = protocol.discover(url).authorizationServers().stream()
                .filter(server -> server.issuer().equals(issuer)).findFirst()
                .orElseThrow(() -> McpException.oauthDiscoveryFailed("The MCP server no longer lists the selected authorization server."));
        UUID id = UUID.randomUUID();
        McpOAuthProtocol.Registration registration;
        if (source == McpOAuthClientSource.METADATA_DOCUMENT) {
            URI document = metadataDocumentOrNull();
            if (!authorizationServer.clientIdMetadataDocumentSupported() || document == null)
                throw McpException.invalid("This authorization server cannot use the MemoryOS client metadata document.");
            registration = new McpOAuthProtocol.Registration(
                    new McpOAuthProtocol.Client(document.toString(), null, McpTokenEndpointAuthMethod.NONE), null, null);
        } else {
            registration = protocol.register(authorizationServer, properties.redirectUri(), CLIENT_NAME);
        }
        return inTransaction(() -> {
            UUID tenant = write(actor);
            oauthServer(tenant, serverId);
            requireCapacity(tenant, serverId);
            requireLabelAvailable(tenant, serverId, validLabel, null);
            Instant now = Instant.now();
            var client = new McpOAuthClientEntity(id, tenant, serverId, source, now);
            var issued = registration.client();
            client.configure(validLabel, authorizationServer.issuer(), issued.clientId(),
                    issued.clientSecret() == null ? null : secrets.seal(tenant, id, McpSecrets.Purpose.OAUTH_CLIENT_SECRET, issued.clientSecret()),
                    issued.method(), authorizationServer.authorizationEndpoint().toString(),
                    authorizationServer.tokenEndpoint().toString(),
                    authorizationServer.revocationEndpoint() == null ? null : authorizationServer.revocationEndpoint().toString(),
                    registration.registrationClientUri() == null ? null : registration.registrationClientUri().toString(),
                    registration.registrationAccessToken() == null ? null
                            : secrets.seal(tenant, id, McpSecrets.Purpose.REGISTRATION_ACCESS_TOKEN, registration.registrationAccessToken()),
                    authorizationServer.issParameterSupported(), now);
            var saved = view(clients.saveAndFlush(client));
            clientChange(tenant, actor, serverId, "REGISTER_" + source.name(), saved);
            return saved;
        });
    }

    @Transactional
    public void deleteClient(ActorId actor, UUID serverId, UUID clientId, long revision) {
        UUID tenant = write(actor);
        var server = oauthServer(tenant, serverId);
        var client = clients.findByTenantIdAndServerIdAndId(tenant, serverId, clientId).orElseThrow(McpException::notFound);
        if (client.revision() != revision) throw McpException.conflict();
        boolean sharedConnection = credentials.findByTenantIdAndServerIdAndOwnerActorIdIsNull(tenant, serverId)
                .filter(credential -> clientId.equals(credential.oauthClientId())).isPresent();
        var deleted = view(client);
        clients.delete(client);
        clientChange(tenant, actor, serverId, "DELETE", deleted);
        if (sharedConnection) {
            server.status(McpServerStatus.AWAITING_AUTH, null, Instant.now());
            servers.saveAndFlush(server);
        }
    }

    /** Builds the administrator's authorization request; the caller keeps {@code state} and the PKCE verifier. */
    @Transactional(readOnly = true)
    public AuthorizationStart startAdministratorAuthorization(ActorId actor, UUID serverId, UUID clientId, String state,
                                                             String codeChallenge) {
        UUID tenant = read(actor);
        var server = oauthServer(tenant, serverId);
        if (server.authPerformer() != McpAuthPerformer.ADMIN)
            throw McpException.invalid("Users connect per-User servers from Chat.");
        var client = clients.findByTenantIdAndServerIdAndId(tenant, serverId, clientId).orElseThrow(McpException::notFound);
        return start(tenant, server, client, state, codeChallenge, null, ADMINISTRATION_PATH);
    }

    /** A User connects their own account to a per-User server they may use; {@code returnPath} is already validated. */
    @Transactional(readOnly = true)
    public AuthorizationStart startUserAuthorization(ActorId actor, UUID serverId, UUID clientId, String state,
                                                     String codeChallenge, String returnPath) {
        UUID tenant = use(actor);
        var server = accessibleOAuthServer(tenant, serverId, actor);
        if (server.authPerformer() != McpAuthPerformer.PER_USER)
            throw McpException.invalid("The administrator manages this server's shared connection.");
        var client = clients.findByTenantIdAndServerIdAndId(tenant, serverId, clientId).orElseThrow(McpException::notFound);
        return start(tenant, server, client, state, codeChallenge, actor.value(), returnPath);
    }

    private AuthorizationStart start(UUID tenant, McpServerEntity server, McpOAuthClientEntity client, String state,
                                     String codeChallenge, @Nullable UUID ownerActorId, String returnPath) {
        String scope = String.join(" ", McpServerRules.stringList(server.oauthScopes()));
        URI url = protocol.authorizationUrl(URI.create(client.authorizationEndpoint()), client.clientId(),
                properties.redirectUri(), state, codeChallenge, scope.isEmpty() ? null : scope,
                McpOAuthProtocol.canonicalResource(server.url()), McpServerRules.stringMap(server.oauthAdditionalParameters()));
        return new AuthorizationStart(url, new Pending(tenant, server.getId(), server.revision(), client.getId(),
                client.revision(), ownerActorId, returnPath));
    }

    /** Completes the administrator connection after the callback validated {@code state}. */
    public void complete(ActorId actor, Pending pending, String code, String verifier, @Nullable String issuer) {
        Exchange exchange = inTransaction(() -> {
            UUID tenant = pending.ownerActorId() == null ? read(actor) : use(actor);
            if (!tenant.equals(pending.tenantId())) throw McpException.conflict();
            requireOwnership(tenant, pending, actor);
            var loaded = pendingTargets(tenant, pending);
            var client = loaded.client();
            if (issuer != null ? !issuer.equals(client.issuer()) : client.issParameterRequired())
                throw McpException.oauthIssuerMismatch();
            return new Exchange(URI.create(client.tokenEndpoint()), protocolClient(client),
                    McpOAuthProtocol.canonicalResource(loaded.server().url()));
        });
        var tokens = protocol.exchangeCode(exchange.tokenEndpoint(), exchange.client(), code, properties.redirectUri(),
                verifier, exchange.resource());
        transactions.executeWithoutResult(status -> {
            UUID owner = pending.ownerActorId();
            UUID tenant = owner == null ? write(actor)
                    : authorization.lockAndRequire(actor, IamCapability.CHAT_WRITE, false).tenantId().value();
            if (!tenant.equals(pending.tenantId())) throw McpException.conflict();
            requireOwnership(tenant, pending, actor);
            var loaded = pendingTargets(tenant, pending);
            Instant now = Instant.now();
            var credential = (owner == null
                    ? credentials.findByTenantIdAndServerIdAndOwnerActorIdIsNull(tenant, pending.serverId())
                    : credentials.findByTenantIdAndServerIdAndOwnerActorId(tenant, pending.serverId(), owner))
                    .orElseGet(() -> new McpCredentialEntity(UUID.randomUUID(), tenant, pending.serverId(), owner, now));
            credential.store(pending.oauthClientId(), seal(tenant, credential.getId(), tokens.accessToken(),
                    tokens.refreshToken(), tokens.scope()), tokens.expiresAt(), now);
            credentials.saveAndFlush(credential);
            // The server status reports the shared connection only; a User's own connection never changes it.
            if (owner == null) {
                loaded.server().status(McpServerStatus.CREATED, null, now);
                servers.saveAndFlush(loaded.server());
                // A User's own connection is not administration; only the shared one is recorded.
                connectionChange(tenant, actor, loaded.server(), "CONNECT");
            }
        });
    }

    /** Removes the User's own connection; the server status reports the shared connection only, so it is untouched. */
    public void disconnectUser(ActorId actor, UUID serverId) {
        Revocation revocation = transactions.execute(status -> {
            UUID tenant = authorization.lockAndRequire(actor, IamCapability.CHAT_WRITE, false).tenantId().value();
            if (!access.accessible(tenant, serverId, actor.value())) throw McpException.notFound();
            var credential = credentials.findByTenantIdAndServerIdAndOwnerActorId(tenant, serverId, actor.value()).orElse(null);
            if (credential == null) return null;
            Revocation pending = revocation(tenant, serverId, credential);
            credentials.delete(credential);
            credentials.flush();
            return pending;
        });
        if (revocation != null) protocol.revoke(revocation.endpoint(), revocation.client(), revocation.token());
    }

    /**
     * Removes the shared connection and revokes it afterwards. This stays separate from
     * {@link #disconnectUser}: the two differ in who may call them, and folding that into a parameter would
     * hide an authorization decision behind a flag.
     */
    public void disconnectAdministrator(ActorId actor, UUID serverId) {
        Revocation revocation = transactions.execute(status -> {
            UUID tenant = write(actor);
            var server = oauthServer(tenant, serverId);
            var credential = credentials.findByTenantIdAndServerIdAndOwnerActorIdIsNull(tenant, serverId).orElse(null);
            if (credential == null) return null;
            Revocation pending = revocation(tenant, serverId, credential);
            credentials.delete(credential);
            server.status(McpServerStatus.AWAITING_AUTH, null, Instant.now());
            servers.saveAndFlush(server);
            connectionChange(tenant, actor, server, "DISCONNECT");
            return pending;
        });
        if (revocation != null) protocol.revoke(revocation.endpoint(), revocation.client(), revocation.token());
    }

    /**
     * A usable access token for the server's shared credential ({@code ownerActorId} null) or a User's own. Callers
     * authorize the request; refresh happens outside transactions and a lost refresh race uses the winner's token.
     */
    public String accessToken(UUID tenantId, UUID serverId, @Nullable UUID ownerActorId) {
        TokenSnapshot snapshot = inTransaction(() -> tokenSnapshot(tenantId, serverId, ownerActorId));
        if (fresh(snapshot.expiresAt())) return snapshot.payload().get(ACCESS_TOKEN);
        String refreshToken = snapshot.payload().get(REFRESH_TOKEN);
        if (refreshToken == null) {
            requireReauthorization(tenantId, serverId, snapshot);
            throw McpException.authorizationRequired();
        }
        McpOAuthProtocol.Tokens tokens;
        try {
            tokens = protocol.refresh(snapshot.tokenEndpoint(), snapshot.client(), refreshToken, snapshot.resource());
        } catch (McpException failure) {
            if (!"MCP_AUTHORIZATION_REQUIRED".equals(failure.code())) throw failure;
            // With refresh-token rotation, a concurrent refresh that won makes this one's grant invalid.
            String winner = winnerToken(tenantId, snapshot);
            if (winner != null) return winner;
            requireReauthorization(tenantId, serverId, snapshot);
            throw failure;
        }
        return inTransaction(() -> {
            var credential = credentials.findById(snapshot.credentialId())
                    .filter(found -> found.tenantId().equals(tenantId)).orElseThrow(McpException::authorizationRequired);
            if (credential.revision() != snapshot.revision()) {
                String winner = open(credential).get(ACCESS_TOKEN);
                if (credential.status() == McpCredentialStatus.ACTIVE && winner != null && fresh(credential.accessExpiresAt()))
                    return winner;
                throw McpException.authorizationRequired();
            }
            Instant now = Instant.now();
            String rotated = tokens.refreshToken() != null ? tokens.refreshToken() : refreshToken;
            credential.store(credential.oauthClientId(), seal(tenantId, credential.getId(), tokens.accessToken(), rotated,
                    tokens.scope()), tokens.expiresAt(), now);
            credentials.saveAndFlush(credential);
            return tokens.accessToken();
        });
    }

    /** Serves the Client ID Metadata Document for authorization servers that fetch it. */
    public Map<String, Object> clientMetadataDocument() {
        URI document = properties.clientMetadataDocumentUrl();
        if (document == null) throw McpException.notFound();
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("client_id", document.toString());
        metadata.put("client_name", CLIENT_NAME);
        metadata.put("redirect_uris", List.of(properties.redirectUri().toString()));
        metadata.put("grant_types", List.of("authorization_code", "refresh_token"));
        metadata.put("response_types", List.of("code"));
        metadata.put("token_endpoint_auth_method", McpTokenEndpointAuthMethod.NONE.wireValue());
        return metadata;
    }

    private record PendingTargets(McpServerEntity server, McpOAuthClientEntity client) {}

    private PendingTargets pendingTargets(UUID tenant, Pending pending) {
        var server = oauthServer(tenant, pending.serverId());
        var client = clients.findByTenantIdAndServerIdAndId(tenant, pending.serverId(), pending.oauthClientId())
                .orElseThrow(McpException::conflict);
        if (server.revision() != pending.serverRevision() || client.revision() != pending.oauthClientRevision()
                || (pending.ownerActorId() == null) != (server.authPerformer() == McpAuthPerformer.ADMIN))
            throw McpException.conflict();
        return new PendingTargets(server, client);
    }

    private TokenSnapshot tokenSnapshot(UUID tenantId, UUID serverId, @Nullable UUID ownerActorId) {
        var credential = (ownerActorId == null
                ? credentials.findByTenantIdAndServerIdAndOwnerActorIdIsNull(tenantId, serverId)
                : credentials.findByTenantIdAndServerIdAndOwnerActorId(tenantId, serverId, ownerActorId))
                .orElseThrow(McpException::authorizationRequired);
        if (credential.status() != McpCredentialStatus.ACTIVE || credential.oauthClientId() == null)
            throw McpException.authorizationRequired();
        var server = servers.findByTenantIdAndId(tenantId, serverId).orElseThrow(McpException::notFound);
        var client = clients.findByTenantIdAndServerIdAndId(tenantId, serverId, credential.oauthClientId())
                .orElseThrow(McpException::authorizationRequired);
        var payload = open(credential);
        if (payload.get(ACCESS_TOKEN) == null) throw McpException.credentialUnreadable();
        return new TokenSnapshot(credential.getId(), credential.revision(), ownerActorId, payload, credential.accessExpiresAt(),
                URI.create(client.tokenEndpoint()), protocolClient(client), McpOAuthProtocol.canonicalResource(server.url()));
    }

    /** A newer, active and fresh token written by a concurrent refresh, or null. */
    private @Nullable String winnerToken(UUID tenantId, TokenSnapshot snapshot) {
        return transactions.execute(status -> credentials.findById(snapshot.credentialId())
                .filter(credential -> credential.tenantId().equals(tenantId) && credential.revision() != snapshot.revision()
                        && credential.status() == McpCredentialStatus.ACTIVE && fresh(credential.accessExpiresAt()))
                .map(credential -> open(credential).get(ACCESS_TOKEN)).orElse(null));
    }

    private void requireReauthorization(UUID tenantId, UUID serverId, TokenSnapshot snapshot) {
        transactions.executeWithoutResult(status -> credentials.findById(snapshot.credentialId())
                .filter(credential -> credential.tenantId().equals(tenantId) && credential.revision() == snapshot.revision())
                .ifPresent(credential -> {
                    Instant now = Instant.now();
                    credential.requireReauthorization(now);
                    credentials.saveAndFlush(credential);
                    if (snapshot.ownerActorId() == null) {
                        servers.findByTenantIdAndId(tenantId, serverId).ifPresent(server -> {
                            server.status(McpServerStatus.AWAITING_AUTH, null, now);
                            servers.saveAndFlush(server);
                        });
                    }
                }));
    }

    private void applyAdminInput(UUID tenant, McpOAuthClientEntity client, @Nullable ClientInput input,
                                 @Nullable String previousSecret, Instant now) {
        if (input == null) throw McpException.invalid("OAuth client configuration is required.");
        String label = McpServerRules.text(input.label(), 100, "label");
        requireLabelAvailable(tenant, client.serverId(), label, client.getId());
        String issuer = McpOAuthProtocol.endpoint(McpServerRules.text(input.issuer(), 2048, "issuer"), false).toString();
        String clientId = McpServerRules.text(input.clientId(), 2048, "client ID");
        McpTokenEndpointAuthMethod method = input.tokenEndpointAuthMethod();
        if (method == null) throw McpException.invalid("Choose how the client authenticates at the token endpoint.");
        String authorizationEndpoint = McpOAuthProtocol.endpoint(McpServerRules.text(input.authorizationEndpoint(), 2048, "authorization endpoint"), true).toString();
        String tokenEndpoint = McpOAuthProtocol.endpoint(McpServerRules.text(input.tokenEndpoint(), 2048, "token endpoint"), false).toString();
        String revocation = input.revocationEndpoint() == null || input.revocationEndpoint().isBlank() ? null
                : McpOAuthProtocol.endpoint(input.revocationEndpoint(), false).toString();
        var change = input.clientSecret();
        if (change == null || change.action() == null) throw McpException.invalid("Choose what to do with the client secret.");
        if (change.action() != McpSecretAction.REPLACE && change.value() != null)
            throw McpException.invalid("Only replacing the client secret accepts a value.");
        String secret = switch (change.action()) {
            case KEEP -> previousSecret;
            case REMOVE -> null;
            case REPLACE -> {
                String value = change.value();
                if (value == null || value.isBlank() || value.length() > MAX_SECRET_CHARACTERS)
                    throw McpException.invalid("The client secret must contain 1 to " + MAX_SECRET_CHARACTERS + " characters.");
                yield secrets.seal(tenant, client.getId(), McpSecrets.Purpose.OAUTH_CLIENT_SECRET, value);
            }
        };
        if ((method == McpTokenEndpointAuthMethod.NONE) != (secret == null))
            throw McpException.invalid("Confidential clients require a secret; public clients have none.");
        client.configure(label, issuer, clientId, secret, method, authorizationEndpoint, tokenEndpoint, revocation,
                null, null, input.issParameterRequired(), now);
    }

    private void requireCapacity(UUID tenant, UUID serverId) {
        if (clients.countByTenantIdAndServerId(tenant, serverId) >= MAX_CLIENTS)
            throw McpException.invalid("This server has reached the OAuth client limit.");
    }

    private void requireLabelAvailable(UUID tenant, UUID serverId, String label, @Nullable UUID except) {
        boolean taken = clients.findByTenantIdAndServerIdOrderByLabelAsc(tenant, serverId).stream()
                .anyMatch(existing -> existing.label().equals(label) && !existing.getId().equals(except));
        if (taken) throw McpException.oauthClientLabelTaken();
    }

    private McpOAuthProtocol.Client protocolClient(McpOAuthClientEntity client) {
        String secret = client.clientSecret() == null ? null
                : secrets.open(client.tenantId(), client.getId(), McpSecrets.Purpose.OAUTH_CLIENT_SECRET, client.clientSecret());
        return new McpOAuthProtocol.Client(client.clientId(), secret, client.tokenEndpointAuthMethod());
    }

    private String seal(UUID tenant, UUID credentialId, String accessToken, @Nullable String refreshToken, @Nullable String scope) {
        var payload = new LinkedHashMap<String, String>();
        payload.put(ACCESS_TOKEN, accessToken);
        if (refreshToken != null) payload.put(REFRESH_TOKEN, refreshToken);
        if (scope != null) payload.put("scope", scope);
        return secrets.seal(tenant, credentialId, McpSecrets.Purpose.CREDENTIAL, McpServerRules.json(payload));
    }

    private Map<String, String> open(McpCredentialEntity credential) {
        return McpServerRules.stringMap(secrets.open(credential.tenantId(), credential.getId(), McpSecrets.Purpose.CREDENTIAL,
                credential.payload()));
    }

    private @Nullable URI metadataDocumentOrNull() {
        try {
            return properties.clientMetadataDocumentUrl();
        } catch (McpException notConfigured) {
            return null;
        }
    }

    private static boolean fresh(@Nullable Instant expiresAt) {
        return expiresAt == null || expiresAt.isAfter(Instant.now().plus(REFRESH_MARGIN));
    }

    /**
     * A User completes only their own authorization, and only while they may still use the server: Group
     * membership can be withdrawn while an authorization is pending without changing any server revision.
     */
    private void requireOwnership(UUID tenant, Pending pending, ActorId actor) {
        UUID owner = pending.ownerActorId();
        if (owner == null) return;
        if (!owner.equals(actor.value())) throw McpException.conflict();
        if (!access.accessible(tenant, pending.serverId(), owner)) throw McpException.notFound();
    }

    /** What to revoke for a credential being deleted, or null when nothing usable is stored. */
    private @Nullable Revocation revocation(UUID tenant, UUID serverId, McpCredentialEntity credential) {
        if (credential.oauthClientId() == null) return null;
        var client = clients.findByTenantIdAndServerIdAndId(tenant, serverId, credential.oauthClientId()).orElse(null);
        if (client == null || client.revocationEndpoint() == null) return null;
        try {
            var payload = open(credential);
            String token = payload.getOrDefault(REFRESH_TOKEN, payload.get(ACCESS_TOKEN));
            return token == null ? null : new Revocation(URI.create(client.revocationEndpoint()), protocolClient(client), token);
        } catch (McpException unreadable) {
            return null;
        }
    }

    /** Tenant of a User who may use MCP servers at all; access to one server is checked separately. */
    private UUID use(ActorId actor) {
        return authorization.require(actor, IamCapability.CHAT_WRITE, false).tenantId().value();
    }

    /** A server the User may use, reported as missing otherwise so restricted servers are not enumerable. */
    private McpServerEntity accessibleOAuthServer(UUID tenant, UUID serverId, ActorId actor) {
        if (!access.accessible(tenant, serverId, actor.value())) throw McpException.notFound();
        return oauthServer(tenant, serverId);
    }

    private McpServerEntity oauthServer(UUID tenant, UUID serverId) {
        var server = servers.findByTenantIdAndId(tenant, serverId).orElseThrow(McpException::notFound);
        if (server.authType() != McpAuthType.OAUTH) throw McpException.invalid("Only OAuth servers use OAuth clients.");
        return server;
    }

    private static ClientView view(McpOAuthClientEntity client) {
        return new ClientView(client.getId(), client.label(), client.source(), client.issuer(), client.clientId(),
                client.clientSecret() != null, client.tokenEndpointAuthMethod(), client.authorizationEndpoint(),
                client.tokenEndpoint(), client.revocationEndpoint(), client.issParameterRequired(), client.revision());
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    private UUID read(ActorId actor) {
        return authorization.require(actor, IamCapability.MCP_MANAGE, false).tenantId().value();
    }

    private UUID write(ActorId actor) {
        return authorization.lockAndRequireExclusive(actor, IamCapability.MCP_MANAGE).tenantId().value();
    }

    private void clientChange(UUID tenant, ActorId actor, UUID serverId, String change, ClientView client) {
        String server = servers.findByTenantIdAndId(tenant, serverId).map(McpServerEntity::name).orElse(null);
        audit.record(AuditRecord.of(AuditAction.MCP_OAUTH_CLIENT_CHANGE, new TenantId(tenant))
                .actor(actor).resource("MCP_SERVER", serverId, server)
                .detail("change", change).detail("client", client.label()).detail("issuer", client.issuer()).build());
    }

    private void connectionChange(UUID tenant, ActorId actor, McpServerEntity server, String change) {
        audit.record(AuditRecord.of(AuditAction.MCP_CONNECTION_CHANGE, new TenantId(tenant))
                .actor(actor).resource("MCP_SERVER", server.getId(), server.name()).detail("change", change).build());
    }
}
