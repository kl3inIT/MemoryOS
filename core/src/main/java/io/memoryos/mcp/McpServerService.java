package io.memoryos.mcp;

import io.memoryos.iam.group.GroupId;
import io.memoryos.iam.group.GroupIdentityPage;
import io.memoryos.iam.group.GroupScopeService;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import io.memoryos.mcp.persistence.JpaMcpCredentialRepository;
import io.memoryos.mcp.persistence.JpaMcpServerRepository;
import io.memoryos.mcp.persistence.JpaMcpServerToolRepository;
import io.memoryos.mcp.persistence.McpCredentialEntity;
import io.memoryos.mcp.persistence.McpServerEntity;
import io.memoryos.mcp.persistence.McpServerToolEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant administration of MCP servers under {@link IamCapability#MCP_MANAGE}. Configuration changes run in
 * transactions; tool refresh calls the server between two transactions and fences the write by revision.
 * Changing the URL, authentication type or performer removes stored credentials so they never reach another
 * endpoint or authentication scheme; the administrator-typed header template is kept.
 */
@Service
public class McpServerService {
    /** Refresh is an explicit administrator action; the configured request timeout still bounds each call. */
    private static final Duration REFRESH_DEADLINE = Duration.ofMinutes(2);

    private final JpaMcpServerRepository servers;
    private final JpaMcpServerToolRepository tools;
    private final JpaMcpCredentialRepository credentials;
    private final IamAuthorization authorization;
    private final GroupScopeService groups;
    private final McpSecrets secrets;
    private final McpClients clients;
    private final McpOAuthService oauth;
    private final TransactionTemplate transactions;

    public McpServerService(JpaMcpServerRepository servers, JpaMcpServerToolRepository tools,
                            JpaMcpCredentialRepository credentials, IamAuthorization authorization,
                            GroupScopeService groups, McpSecrets secrets, McpClients clients, McpOAuthService oauth,
                            PlatformTransactionManager transactionManager) {
        this.servers = servers; this.tools = tools; this.credentials = credentials; this.authorization = authorization;
        this.groups = groups; this.secrets = secrets; this.clients = clients; this.oauth = oauth;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public record HeaderChange(McpSecretAction action, @Nullable Map<String, String> values) {
        @Override public @NonNull String toString() { return "McpHeaderChange[redacted]"; }
    }

    public record SecretChange(McpSecretAction action, @Nullable String value) {
        @Override public @NonNull String toString() { return "McpSecretChange[redacted]"; }
    }

    public record ServerInput(String name, @Nullable String description, String url, McpAuthType authType,
                              McpAuthPerformer authPerformer, @Nullable McpOAuthProviderMode oauthProviderMode,
                              List<String> oauthScopes, Map<String, String> oauthAdditionalParameters,
                              HeaderChange headers, SecretChange sharedApiKey, boolean tenantWide, Set<UUID> groupIds) {
        @Override public @NonNull String toString() { return "McpServerInput[redacted]"; }
    }

    public record ServerView(UUID id, String slug, String name, @Nullable String description, String url,
                             McpAuthType authType, McpAuthPerformer authPerformer,
                             @Nullable McpOAuthProviderMode oauthProviderMode, List<String> oauthScopes,
                             Map<String, String> oauthAdditionalParameters, List<String> headerNames,
                             boolean sharedCredentialConfigured, boolean tenantWide, Set<UUID> groupIds,
                             McpServerStatus status, @Nullable Instant lastRefreshedAt, long toolCount,
                             long enabledToolCount, long revision) {}

    /** {@code exposable} is false when {@code mcp_<slug>_<name>} does not fit model tool-name limits. */
    public record ToolView(UUID id, String name, String modelName, @Nullable String title, String description,
                           @Nullable Boolean readOnlyHint, @Nullable Boolean destructiveHint, boolean enabled,
                           boolean exposable, Instant snapshotAt, long revision) {}

    public record Refresh(ServerView server, List<ToolView> tools) {}

    /** {@code ownerActorId} is the refreshing administrator's own credential on a per-User server, else null. */
    private record Target(UUID tenantId, String url, Map<String, String> headers, long revision, boolean oauth,
                          @Nullable UUID ownerActorId) {
        @Override public @NonNull String toString() { return "McpRefreshTarget[redacted]"; }
    }

    @Transactional(readOnly = true)
    public List<ServerView> list(ActorId actor) {
        UUID tenant = read(actor);
        return servers.findByTenantIdOrderByNameAsc(tenant).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ServerView get(ActorId actor, UUID serverId) {
        return view(server(read(actor), serverId));
    }

    /** Groups an MCP manager may grant server access to. */
    @Transactional
    public GroupIdentityPage groupOptions(ActorId actor, @Nullable String search, int page, int size) {
        var access = authorization.lockAndRequire(actor, IamCapability.MCP_MANAGE, false);
        return groups.listGroupOptions(access.tenantId(), search, page, size);
    }

    @Transactional
    public ServerView create(ActorId actor, @Nullable String slug, ServerInput input) {
        UUID tenant = write(actor);
        String validSlug = McpServerRules.slug(slug);
        if (servers.countByTenantId(tenant) >= McpServerRules.MAX_SERVERS)
            throw McpException.invalid("This organization has reached the MCP server limit.");
        if (servers.existsByTenantIdAndSlug(tenant, validSlug)) throw McpException.slugTaken();
        Instant now = Instant.now();
        return save(tenant, new McpServerEntity(UUID.randomUUID(), tenant, validSlug, now), input, true, now);
    }

    @Transactional
    public ServerView update(ActorId actor, UUID serverId, long revision, @Nullable String slug, ServerInput input) {
        UUID tenant = write(actor);
        var server = server(tenant, serverId);
        if (server.revision() != revision) throw McpException.conflict();
        if (!server.slug().equals(slug)) throw McpException.invalid("The slug of an MCP server cannot change.");
        return save(tenant, server, input, false, Instant.now());
    }

    @Transactional
    public void delete(ActorId actor, UUID serverId, long revision) {
        UUID tenant = write(actor);
        var server = server(tenant, serverId);
        if (server.revision() != revision) throw McpException.conflict();
        servers.delete(server);
    }

    @Transactional(readOnly = true)
    public List<ToolView> tools(ActorId actor, UUID serverId) {
        return toolViews(server(read(actor), serverId));
    }

    @Transactional
    public ToolView setToolEnabled(ActorId actor, UUID serverId, UUID toolId, long revision, boolean enabled) {
        UUID tenant = write(actor);
        var server = server(tenant, serverId);
        var tool = tools.findByTenantIdAndServerIdAndId(tenant, serverId, toolId).orElseThrow(McpException::notFound);
        if (tool.revision() != revision) throw McpException.conflict();
        if (enabled && McpServerRules.modelToolName(server.slug(), tool.name()).isEmpty())
            throw McpException.toolNameUnsupported();
        tool.enabled(enabled);
        return toolView(server, tools.saveAndFlush(tool));
    }

    /** Enables every exposable tool, or disables every tool. */
    @Transactional
    public List<ToolView> setAllToolsEnabled(ActorId actor, UUID serverId, boolean enabled) {
        UUID tenant = write(actor);
        var server = server(tenant, serverId);
        for (var tool : tools.findByTenantIdAndServerIdOrderByNameAsc(tenant, serverId)) {
            if (!enabled || McpServerRules.modelToolName(server.slug(), tool.name()).isPresent()) tool.enabled(enabled);
        }
        tools.flush();
        return toolViews(server);
    }

    /** Lists the server's tools with the administrator credential and replaces the stored snapshot. */
    public Refresh refreshTools(ActorId actor, UUID serverId) {
        Target target = Objects.requireNonNull(transactions.execute(status -> {
            UUID tenant = read(actor);
            var server = server(tenant, serverId);
            UUID owner = server.authPerformer() == McpAuthPerformer.PER_USER ? actor.value() : null;
            return new Target(tenant, server.url(), headers(server, owner), server.revision(),
                    server.authType() == McpAuthType.OAUTH, owner);
        }));
        List<McpToolDescriptor> listed;
        try {
            Map<String, String> headers = target.headers();
            if (target.oauth()) {
                var authorized = new LinkedHashMap<>(headers);
                authorized.put("Authorization", "Bearer " + oauth.accessToken(target.tenantId(), serverId, target.ownerActorId()));
                headers = authorized;
            }
            try (var session = clients.open(target.url(), headers, REFRESH_DEADLINE)) {
                listed = session.listTools();
            }
        } catch (McpException failure) {
            boolean unauthorized = "MCP_AUTHORIZATION_REQUIRED".equals(failure.code());
            // AWAITING_AUTH reports a missing shared connection, so one administrator's own credential never sets it.
            if (!unauthorized || target.ownerActorId() == null)
                recordFailure(actor, serverId, target.revision(),
                        unauthorized ? McpServerStatus.AWAITING_AUTH : McpServerStatus.DISCONNECTED);
            throw failure;
        }
        var snapshot = McpServerRules.snapshot(listed);
        return Objects.requireNonNull(transactions.execute(status -> {
            UUID tenant = write(actor);
            var server = server(tenant, serverId);
            if (server.revision() != target.revision()) throw McpException.conflict();
            Instant now = Instant.now();
            Map<String, McpServerToolEntity> existing = tools.findByTenantIdAndServerIdOrderByNameAsc(tenant, serverId)
                    .stream().collect(Collectors.toMap(McpServerToolEntity::name, Function.identity()));
            var listedNames = new HashSet<String>();
            for (var tool : snapshot) {
                listedNames.add(tool.name());
                var entity = existing.get(tool.name());
                if (entity == null) entity = new McpServerToolEntity(UUID.randomUUID(), tenant, serverId, tool.name());
                entity.snapshot(tool.title(), tool.description(), tool.inputSchema(), tool.annotations(), tool.readOnly(), now);
                tools.save(entity);
            }
            tools.deleteAll(existing.values().stream().filter(tool -> !listedNames.contains(tool.name())).toList());
            server.status(McpServerStatus.CONNECTED, now, now);
            servers.saveAndFlush(server);
            return new Refresh(view(server), toolViews(server));
        }));
    }

    private ServerView save(UUID tenant, McpServerEntity server, ServerInput input, boolean created, Instant now) {
        if (input == null) throw McpException.invalid("MCP server configuration is required.");
        String name = McpServerRules.text(input.name(), 200, "name");
        String description = McpServerRules.optionalText(input.description(), 2000, "description");
        String url = McpServerRules.url(input.url());
        McpAuthType authType = input.authType();
        McpAuthPerformer performer = input.authPerformer();
        if (authType == null || performer == null) throw McpException.invalid("Choose how the server authenticates.");
        if (authType == McpAuthType.NONE && performer != McpAuthPerformer.ADMIN)
            throw McpException.invalid("A server without authentication uses one shared connection.");
        if ((authType == McpAuthType.OAUTH) != (input.oauthProviderMode() != null))
            throw McpException.invalid("Only OAuth servers take an OAuth provider mode, and they require one.");
        var scopes = McpServerRules.scopes(input.oauthScopes());
        var parameters = McpServerRules.parameters(input.oauthAdditionalParameters());
        if (authType != McpAuthType.OAUTH && (!scopes.isEmpty() || !parameters.isEmpty()))
            throw McpException.invalid("Only OAuth servers take scopes or authorization parameters.");
        Set<UUID> groupIds = accessGroups(tenant, input.tenantWide(), input.groupIds());

        boolean urlChanged = !created && !server.url().equals(url);
        boolean connectionChanged = created || urlChanged || server.authType() != authType || server.authPerformer() != performer;
        String headerTemplate = headerTemplate(tenant, server, input.headers(), authType, created);

        SecretChange key = input.sharedApiKey();
        if (key == null || key.action() == null) throw McpException.invalid("Choose what to do with the shared API key.");
        if (key.action() != McpSecretAction.REPLACE && key.value() != null)
            throw McpException.invalid("Only replacing the shared API key accepts a value.");
        boolean sharedKeyAccepted = authType == McpAuthType.API_TOKEN && performer == McpAuthPerformer.ADMIN;
        if (key.action() != McpSecretAction.KEEP && !sharedKeyAccepted)
            throw McpException.invalid("Only API-key servers with a shared connection store a shared API key.");
        String apiKey = key.action() == McpSecretAction.REPLACE ? McpServerRules.apiKey(key.value()) : null;
        var shared = created ? null : credentials.findByTenantIdAndServerIdAndOwnerActorIdIsNull(tenant, server.getId()).orElse(null);
        boolean sharedAfter = switch (key.action()) {
            case KEEP -> shared != null && !connectionChanged;
            case REPLACE -> true;
            case REMOVE -> false;
        };

        server.configure(name, description, url, authType, performer, input.oauthProviderMode(), McpServerRules.json(scopes),
                McpServerRules.json(parameters), headerTemplate, input.tenantWide(), groupIds, now);
        if (urlChanged) server.reconnect(readiness(authType, performer, sharedAfter), now);
        else if (connectionChanged || key.action() != McpSecretAction.KEEP)
            server.status(readiness(authType, performer, sharedAfter), null, now);
        servers.saveAndFlush(server);

        if (!created && connectionChanged) credentials.deleteAll(credentials.findByTenantIdAndServerId(tenant, server.getId()));
        if (urlChanged) tools.deleteAll(tools.findByTenantIdAndServerIdOrderByNameAsc(tenant, server.getId()));
        if (key.action() == McpSecretAction.REMOVE && shared != null && !connectionChanged) credentials.delete(shared);
        // Hibernate flushes inserts before deletes; a replacement shared credential would hit uq_mcp_credential_shared.
        credentials.flush();
        if (apiKey != null) {
            var credential = shared != null && !connectionChanged ? shared
                    : new McpCredentialEntity(UUID.randomUUID(), tenant, server.getId(), null, now);
            credential.store(null, secrets.seal(tenant, credential.getId(), McpSecrets.Purpose.CREDENTIAL,
                    McpServerRules.json(Map.of(McpServerRules.API_KEY, apiKey))), null, now);
            credentials.save(credential);
        }
        credentials.flush();
        return view(server);
    }

    private @Nullable String headerTemplate(UUID tenant, McpServerEntity server, @Nullable HeaderChange change,
                                            McpAuthType authType, boolean created) {
        if (change == null || change.action() == null) throw McpException.invalid("Choose what to do with the header template.");
        if (change.action() != McpSecretAction.REPLACE && change.values() != null)
            throw McpException.invalid("Only replacing the header template accepts headers.");
        return switch (change.action()) {
            case KEEP -> {
                String previous = created ? null : server.headerTemplate();
                // Revalidate against a changed authentication type, e.g. {api_key} on a server that no longer has one.
                if (previous != null) McpServerRules.headerTemplate(openTemplate(server), authType);
                yield previous;
            }
            case REMOVE -> null;
            case REPLACE -> {
                var headers = McpServerRules.headerTemplate(change.values(), authType);
                yield headers.isEmpty() ? null : secrets.seal(tenant, server.getId(), McpSecrets.Purpose.SERVER_HEADERS,
                        McpServerRules.json(headers));
            }
        };
    }

    private Set<UUID> accessGroups(UUID tenant, boolean tenantWide, @Nullable Set<UUID> groupIds) {
        if (groupIds == null || groupIds.size() > McpServerRules.MAX_GROUPS || groupIds.stream().anyMatch(Objects::isNull))
            throw McpException.invalid("Provide at most " + McpServerRules.MAX_GROUPS + " Groups.");
        if (tenantWide && !groupIds.isEmpty()) throw McpException.invalid("Choose organization-wide access or Groups, not both.");
        if (!tenantWide && groupIds.isEmpty()) throw McpException.invalid("Choose organization-wide access or at least one Group.");
        if (!groupIds.isEmpty()) groups.validateGroupIds(new TenantId(tenant), groupIds.stream().map(GroupId::new).toList());
        return Set.copyOf(groupIds);
    }

    private static McpServerStatus readiness(McpAuthType authType, McpAuthPerformer performer, boolean sharedCredential) {
        if (performer == McpAuthPerformer.PER_USER || authType == McpAuthType.NONE) return McpServerStatus.CREATED;
        return sharedCredential ? McpServerStatus.CREATED : McpServerStatus.AWAITING_AUTH;
    }

    /**
     * Headers for a refresh. {@code ownerActorId} selects the refreshing administrator's own credential on a
     * per-User server and is null for the shared one; OAuth adds the bearer token outside the transaction.
     */
    private Map<String, String> headers(McpServerEntity server, @Nullable UUID ownerActorId) {
        Map<String, String> template = server.headerTemplate() == null ? null : openTemplate(server);
        return switch (server.authType()) {
            case NONE -> McpServerRules.resolveHeaders(template, McpAuthType.NONE, null);
            case API_TOKEN -> {
                var credential = (ownerActorId == null
                        ? credentials.findByTenantIdAndServerIdAndOwnerActorIdIsNull(server.tenantId(), server.getId())
                        : credentials.findByTenantIdAndServerIdAndOwnerActorId(server.tenantId(), server.getId(), ownerActorId))
                        .orElseThrow(McpException::credentialRequired);
                String apiKey = McpServerRules.stringMap(secrets.open(server.tenantId(), credential.getId(),
                        McpSecrets.Purpose.CREDENTIAL, credential.payload())).get(McpServerRules.API_KEY);
                if (apiKey == null) throw McpException.credentialUnreadable();
                yield McpServerRules.resolveHeaders(template, McpAuthType.API_TOKEN, apiKey);
            }
            case OAUTH -> McpServerRules.resolveHeaders(template, McpAuthType.OAUTH, null);
        };
    }

    private Map<String, String> openTemplate(McpServerEntity server) {
        return McpServerRules.stringMap(secrets.open(server.tenantId(), server.getId(), McpSecrets.Purpose.SERVER_HEADERS,
                Objects.requireNonNull(server.headerTemplate())));
    }

    private void recordFailure(ActorId actor, UUID serverId, long revision, McpServerStatus status) {
        try {
            transactions.executeWithoutResult(transaction -> {
                var server = server(write(actor), serverId);
                if (server.revision() == revision && server.status() != status) {
                    server.status(status, null, Instant.now());
                    servers.saveAndFlush(server);
                }
            });
        } catch (RuntimeException ignored) {
            // The refresh failure the caller receives matters more than a status that could not be recorded.
        }
    }

    private ServerView view(McpServerEntity server) {
        List<String> headerNames;
        try {
            headerNames = server.headerTemplate() == null ? List.of() : List.copyOf(openTemplate(server).keySet());
        } catch (McpException unreadable) {
            headerNames = List.of();
        }
        return new ServerView(server.getId(), server.slug(), server.name(), server.description(), server.url(),
                server.authType(), server.authPerformer(), server.oauthProviderMode(),
                McpServerRules.stringList(server.oauthScopes()), McpServerRules.stringMap(server.oauthAdditionalParameters()),
                headerNames, credentials.findByTenantIdAndServerIdAndOwnerActorIdIsNull(server.tenantId(), server.getId()).isPresent(),
                server.tenantWide(), server.groupIds(), server.status(), server.lastRefreshedAt(),
                tools.countByTenantIdAndServerId(server.tenantId(), server.getId()),
                tools.countByTenantIdAndServerIdAndEnabledTrue(server.tenantId(), server.getId()), server.revision());
    }

    private List<ToolView> toolViews(McpServerEntity server) {
        return tools.findByTenantIdAndServerIdOrderByNameAsc(server.tenantId(), server.getId()).stream()
                .map(tool -> toolView(server, tool)).toList();
    }

    private static ToolView toolView(McpServerEntity server, McpServerToolEntity tool) {
        var modelName = McpServerRules.modelToolName(server.slug(), tool.name());
        return new ToolView(tool.getId(), tool.name(), modelName.orElse(""), tool.title(), tool.description(),
                McpServerRules.hint(tool.annotations(), "readOnlyHint"), McpServerRules.hint(tool.annotations(), "destructiveHint"),
                tool.enabled(), modelName.isPresent(), tool.snapshotAt(), tool.revision());
    }

    private McpServerEntity server(UUID tenant, UUID serverId) {
        return servers.findByTenantIdAndId(tenant, serverId).orElseThrow(McpException::notFound);
    }

    private UUID read(ActorId actor) {
        return authorization.require(actor, IamCapability.MCP_MANAGE, false).tenantId().value();
    }

    private UUID write(ActorId actor) {
        return authorization.lockAndRequireExclusive(actor, IamCapability.MCP_MANAGE).tenantId().value();
    }
}
