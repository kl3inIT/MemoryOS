package io.memoryos.mcp;

import java.util.HashSet;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.mcp.persistence.McpCredentialEntity;
import io.memoryos.shared.ActorId;
import io.memoryos.mcp.persistence.JpaMcpCredentialRepository;
import io.memoryos.mcp.persistence.JpaMcpServerRepository;
import io.memoryos.mcp.persistence.JpaMcpServerToolRepository;
import io.memoryos.mcp.persistence.McpAccessRepository;
import io.memoryos.mcp.persistence.McpServerEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves the MCP servers chosen for one Chat turn into callable tools. Access, credentials and OAuth refresh
 * are settled here, before the model runs, so a turn never blocks on an authorization server mid-call.
 */
@Service
public class McpTurnService {
    /**
     * A turn offers at most this many servers. A large server's tools wait behind one opening tool (MEM-272), so the
     * number of its tools is no longer bounded per turn.
     */
    public static final int MAX_SERVERS = 8;

    private final JpaMcpServerRepository servers;
    private final JpaMcpServerToolRepository tools;
    private final JpaMcpCredentialRepository credentials;
    private final McpAccessRepository access;
    private final IamAuthorization authorization;
    private final McpSecrets secrets;
    private final McpClients clients;
    private final McpServerService serverAdministration;
    private final McpOAuthService oauth;
    private final TransactionTemplate transactions;

    public McpTurnService(JpaMcpServerRepository servers, JpaMcpServerToolRepository tools,
                          JpaMcpCredentialRepository credentials, McpAccessRepository access,
                          IamAuthorization authorization, McpSecrets secrets, McpClients clients,
                          McpOAuthService oauth, McpServerService serverAdministration,
                          PlatformTransactionManager transactionManager) {
        this.servers = servers; this.tools = tools; this.credentials = credentials; this.access = access;
        this.authorization = authorization; this.secrets = secrets; this.clients = clients; this.oauth = oauth;
        this.serverAdministration = serverAdministration;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Opens the turn's tools. Servers the actor may not use are ignored rather than reported, matching the
     * connections API. The result is closed by the caller when the turn ends.
     */
    public McpTurnTools open(ActorId actor, List<UUID> serverIds) {
        if (serverIds == null || serverIds.isEmpty()) return empty();
        if (serverIds.size() > MAX_SERVERS)
            throw McpException.invalid("A turn uses at most " + MAX_SERVERS + " MCP servers.");
        var resolved = Objects.requireNonNull(transactions.execute(_ -> resolve(actor, serverIds)));
        var targets = new LinkedHashMap<UUID, McpTurnTools.Target>();
        var bindings = new ArrayList<McpTurnTools.Binding>();
        var unavailable = new ArrayList<>(resolved.unavailable());
        for (var candidate : resolved.candidates()) {
            Map<String, String> headers;
            try {
                headers = authorize(candidate);
            } catch (McpException failure) {
                // Refresh happens before the model runs, so a rejected credential becomes a connect action.
                unavailable.add(new McpTurnTools.Unavailable(candidate.serverId(), candidate.serverName(),
                        McpTurnTools.Unavailable.Reason.REAUTH_REQUIRED));
                continue;
            }
            targets.put(candidate.serverId(), new McpTurnTools.Target(candidate.serverId(), candidate.url(), headers));
            bindings.addAll(candidate.bindings());
        }
        return new McpTurnTools(clients, targets, bindings, unavailable, resolved.alwaysAllowable());
    }

    /** A turn that selected no usable server; only {@link #open} needs it. */
    private McpTurnTools empty() {
        return new McpTurnTools(clients, Map.of(), List.of(), List.of(), Set.of());
    }

    private record Candidate(UUID serverId, String serverName, String url, McpAuthType authType,
                             @Nullable UUID ownerActorId, @Nullable String headerTemplate, @Nullable String apiKey,
                             List<McpTurnTools.Binding> bindings) {}

    private record Resolved(List<Candidate> candidates, List<McpTurnTools.Unavailable> unavailable,
                            Set<String> alwaysAllowable) {}

    /**
     * "Always allow" answered on an approval card (MEM-267): the tool stops asking for everyone. The server checks
     * again that the person manages MCP servers and that the tool is not destructive.
     */
    public void allowAlways(ActorId actor, McpTurnTools.Binding binding) {
        serverAdministration.allowAlways(actor, binding.serverId(), binding.toolName());
    }

    private Resolved resolve(ActorId actor, List<UUID> serverIds) {
        UUID tenant = authorization.require(actor, IamCapability.CHAT_WRITE, false).tenantId().value();
        Set<UUID> accessible = access.accessibleServerIds(tenant, actor.value());
        var candidates = new ArrayList<Candidate>();
        var unavailable = new ArrayList<McpTurnTools.Unavailable>();
        var alwaysAllowable = new HashSet<String>();
        boolean manages = authorization.effectiveCapabilities(actor).contains(IamCapability.MCP_MANAGE);
        // Servers in ID order and tools by name, so a conversation's tool list, which leads the provider's cached prefix,
        // stays the same from turn to turn (MEM-269).
        for (UUID serverId : serverIds.stream().distinct().sorted().toList()) {
            if (!accessible.contains(serverId)) continue;
            var server = servers.findByTenantIdAndId(tenant, serverId).orElse(null);
            if (server == null) continue;
            UUID owner = server.authPerformer() == McpAuthPerformer.PER_USER ? actor.value() : null;
            var credential = server.authType() == McpAuthType.NONE ? null
                    : (owner == null
                            ? credentials.findByTenantIdAndServerIdAndOwnerActorIdIsNull(tenant, serverId)
                            : credentials.findByTenantIdAndServerIdAndOwnerActorId(tenant, serverId, owner)).orElse(null);
            if (server.authType() != McpAuthType.NONE
                    && (credential == null || credential.status() != McpCredentialStatus.ACTIVE)) {
                unavailable.add(new McpTurnTools.Unavailable(serverId, server.name(), credential == null
                        ? McpTurnTools.Unavailable.Reason.NOT_CONNECTED
                        : McpTurnTools.Unavailable.Reason.REAUTH_REQUIRED));
                continue;
            }
            var bindings = new ArrayList<McpTurnTools.Binding>();
            for (var tool : tools.findByTenantIdAndServerIdOrderByNameAsc(tenant, serverId)) {
                if (tool.policy() == McpToolPolicy.OFF) continue;
                var modelName = McpServerRules.modelToolName(server.slug(), tool.name());
                if (modelName.isEmpty()) continue;
                bindings.add(new McpTurnTools.Binding(serverId, server.slug(), server.name(), tool.name(), modelName.get(),
                        tool.description(), tool.inputSchema(), tool.readOnly(), tool.policy() == McpToolPolicy.ASK));
                if (manages && tool.policy() == McpToolPolicy.ASK
                        && McpServerRules.allowed(McpServerRules.risk(tool.readOnly(), tool.annotations()), McpToolPolicy.ALWAYS))
                    alwaysAllowable.add(modelName.get());
            }
            if (bindings.isEmpty()) {
                unavailable.add(new McpTurnTools.Unavailable(serverId, server.name(),
                        McpTurnTools.Unavailable.Reason.NO_TOOLS));
                continue;
            }
            candidates.add(new Candidate(serverId, server.name(), server.url(), server.authType(), owner,
                    server.headerTemplate() == null ? null : template(server), apiKey(tenant, credential), bindings));
        }
        return new Resolved(candidates, unavailable, alwaysAllowable);
    }

    /** Resolves headers outside the transaction; OAuth refresh may call the authorization server. */
    private Map<String, String> authorize(Candidate candidate) {
        Map<String, String> template = candidate.headerTemplate() == null ? null
                : McpServerRules.stringMap(candidate.headerTemplate());
        var headers = McpServerRules.resolveHeaders(template, candidate.authType(), candidate.apiKey());
        if (candidate.authType() != McpAuthType.OAUTH) return headers;
        var authorized = new LinkedHashMap<>(headers);
        UUID tenant = Objects.requireNonNull(transactions.execute(_ ->
                servers.findById(candidate.serverId()).map(McpServerEntity::tenantId).orElseThrow(McpException::notFound)));
        authorized.put("Authorization", "Bearer " + oauth.accessToken(tenant, candidate.serverId(), candidate.ownerActorId()));
        return Map.copyOf(authorized);
    }

    private String template(McpServerEntity server) {
        return secrets.open(server.tenantId(), server.getId(), McpSecrets.Purpose.SERVER_HEADERS,
                Objects.requireNonNull(server.headerTemplate()));
    }

    private @Nullable String apiKey(UUID tenant, @Nullable McpCredentialEntity credential) {
        if (credential == null) return null;
        return McpServerRules.stringMap(secrets.open(tenant, credential.getId(), McpSecrets.Purpose.CREDENTIAL,
                credential.payload())).get(McpServerRules.API_KEY);
    }
}
