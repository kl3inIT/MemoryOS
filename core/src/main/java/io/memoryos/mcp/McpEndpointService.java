package io.memoryos.mcp;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.mcp.persistence.JdbcMcpEndpointRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.net.URI;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MEM-114: the per-Tenant switch for the MemoryOS MCP endpoint, through which Claude and ChatGPT search and read the
 * Tenant's knowledge as the signed-in member. It is off until an administrator turns it on, and only a deployment that
 * configured the endpoint URL can turn it on.
 */
@Service
public class McpEndpointService {
    private final JdbcMcpEndpointRepository repository;
    private final McpEndpointProperties properties;
    private final IamAuthorization authorization;
    private final AuditTrail audit;
    private final McpTrustedAppService trustedApps;

    public McpEndpointService(JdbcMcpEndpointRepository repository, McpEndpointProperties properties,
                              IamAuthorization authorization, AuditTrail audit, McpTrustedAppService trustedApps) {
        this.repository = repository;
        this.properties = properties;
        this.authorization = authorization;
        this.audit = audit;
        this.trustedApps = trustedApps;
    }

    /** {@code url} is where people point their client, present only when the deployment configured it. */
    public record Settings(boolean configured, boolean enabled, long revision, @Nullable URI url) {}

    /**
     * What a member needs to connect a client: whether the endpoint answers, its URL while it does, and the apps the
     * administrator trusts, so the guide shows only those ({@code CUSTOM} when any app of the Tenant's own is).
     */
    public record Connection(boolean available, @Nullable URI url, Set<McpTrustedApp.Preset> apps) {}

    @Transactional(readOnly = true)
    public Settings settings(ActorId actor) {
        var tenant = authorization.require(actor, IamCapability.MCP_MANAGE, false).tenantId();
        var setting = repository.setting(tenant);
        return settings(setting.map(JdbcMcpEndpointRepository.Setting::enabled).orElse(false),
                setting.map(JdbcMcpEndpointRepository.Setting::revision).orElse(0L));
    }

    @Transactional
    public Settings update(ActorId actor, boolean enabled, long revision) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MCP_MANAGE).tenantId();
        long current = repository.setting(tenant).map(JdbcMcpEndpointRepository.Setting::revision).orElse(0L);
        if (current != revision) throw McpException.endpointChanged();
        if (enabled && !properties.configured()) throw McpException.endpointNotConfigured();
        var saved = repository.save(tenant, enabled);
        audit.record(AuditRecord.of(AuditAction.MCP_ENDPOINT_CHANGE, tenant).actor(actor)
                .resource("SETTING", "mcp_endpoint", "MCP endpoint").detail("enabled", enabled).build());
        return settings(saved.enabled(), saved.revision());
    }

    @Transactional(readOnly = true)
    public Connection connection(ActorId actor) {
        var tenant = authorization.require(actor, IamCapability.SEARCH_READ, false).tenantId();
        return available(tenant)
                ? new Connection(true, properties.url().orElse(null), trustedApps.enabled(tenant))
                : new Connection(false, null, Set.of());
    }

    /** Checked on every request to the endpoint, so turning the switch off refuses the next call. */
    @Transactional(readOnly = true)
    public boolean available(TenantId tenant) {
        return properties.configured()
                && repository.setting(tenant).map(JdbcMcpEndpointRepository.Setting::enabled).orElse(false);
    }

    private Settings settings(boolean enabled, long revision) {
        return new Settings(properties.configured(), enabled, revision, properties.url().orElse(null));
    }
}
