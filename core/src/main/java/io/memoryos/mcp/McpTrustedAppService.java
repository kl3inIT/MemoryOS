package io.memoryos.mcp;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.McpClientPolicy;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.mcp.persistence.JdbcMcpTrustedAppRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MEM-207: which outside assistants the MCP endpoint admits, as Notion and Atlassian let an administrator approve the
 * AI apps that may connect. The database holds what the administrator chose; Keycloak's metadata-document policy is a
 * projection of the operating Tenant's enabled apps. A change writes the policy inside its transaction, so a Keycloak
 * failure leaves nothing changed, and {@link #reconcile()} repairs a policy that drifted, at start and periodically.
 */
@Service
public class McpTrustedAppService {
    private static final int MAX_CUSTOM_APPS = 50;
    private static final int MAX_CLIENT_ID_HOSTS = 10;
    private static final int MAX_DOCUMENT_HOSTS = 20;
    private static final Pattern HOST =
            Pattern.compile("(\\*\\.)?([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1");

    private final JdbcMcpTrustedAppRepository repository;
    private final McpClientPolicy policy;
    private final IamAuthorization authorization;
    private final TenantAccessResolver tenants;
    private final AuditTrail audit;

    public McpTrustedAppService(JdbcMcpTrustedAppRepository repository, McpClientPolicy policy,
                                IamAuthorization authorization, TenantAccessResolver tenants, AuditTrail audit) {
        this.repository = repository;
        this.policy = policy;
        this.authorization = authorization;
        this.tenants = tenants;
        this.audit = audit;
    }

    /** {@code manageable} is false when the deployment has no account to change Keycloak's policy. */
    public record Listing(boolean manageable, List<McpTrustedApp> apps) {}

    @Transactional
    public Listing list(ActorId actor) {
        var tenant = authorization.require(actor, IamCapability.MCP_MANAGE, false).tenantId();
        repository.ensureBuiltIns(tenant);
        return new Listing(policy.configured(), repository.list(tenant).stream().map(McpTrustedAppService::resolved).toList());
    }

    /** The built-in apps a member may connect, and whether any app of the Tenant's own is trusted. */
    @Transactional(readOnly = true)
    public Set<McpTrustedApp.Preset> enabled(TenantId tenant) {
        var enabled = EnumSet.copyOf(McpTrustedApp.Preset.builtIns());
        for (var app : repository.list(tenant)) {
            if (app.builtIn() && !app.enabled()) enabled.remove(app.preset());
            if (!app.builtIn() && app.enabled()) enabled.add(McpTrustedApp.Preset.CUSTOM);
        }
        return enabled;
    }

    @Transactional
    public McpTrustedApp add(ActorId actor, String name, List<String> clientIdHosts, List<String> documentHosts) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MCP_MANAGE).tenantId();
        requireManageable();
        String label = name == null ? "" : name.strip();
        if (label.isEmpty() || label.length() > 80) throw McpException.invalid("Name the app in 1 to 80 characters.");
        var clientId = hosts(clientIdHosts, false);
        if (clientId.isEmpty() || clientId.size() > MAX_CLIENT_ID_HOSTS) {
            throw McpException.invalid("Give 1 to 10 domains for the address of the app's metadata document.");
        }
        var document = new LinkedHashSet<>(clientId);
        document.addAll(hosts(documentHosts, true));
        if (document.size() > MAX_DOCUMENT_HOSTS) throw McpException.invalid("Give at most 20 domains in all.");
        if (repository.customCount(tenant) >= MAX_CUSTOM_APPS) throw McpException.invalid("Trust at most 50 apps.");
        var app = repository.insertCustom(tenant, label, List.copyOf(clientId), List.copyOf(document));
        project(tenant);
        record(actor, tenant, app, "added");
        return app;
    }

    @Transactional
    public McpTrustedApp setEnabled(ActorId actor, UUID id, boolean enabled, long revision) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MCP_MANAGE).tenantId();
        requireManageable();
        repository.ensureBuiltIns(tenant);
        var app = repository.setEnabled(tenant, id, enabled, revision).map(McpTrustedAppService::resolved)
                .orElseThrow(() -> missingOrChanged(tenant, id));
        project(tenant);
        record(actor, tenant, app, enabled ? "enabled" : "disabled");
        return app;
    }

    @Transactional
    public void remove(ActorId actor, UUID id, long revision) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MCP_MANAGE).tenantId();
        requireManageable();
        var app = repository.find(tenant, id).orElseThrow(McpException::trustedAppNotFound);
        if (app.builtIn()) throw McpException.invalid("Claude and ChatGPT can be switched off but not removed.");
        if (!repository.deleteCustom(tenant, id, revision)) throw missingOrChanged(tenant, id);
        project(tenant);
        record(actor, tenant, app, "removed");
    }

    /**
     * Makes Keycloak trust exactly the operating Tenant's enabled apps and removes the clients of any other app, which
     * repairs a change whose transaction failed after Keycloak took it. A deployment without the account skips it.
     */
    @Transactional(readOnly = true)
    public void reconcile() {
        if (!policy.configured()) return;
        tenants.operatingTenant().ifPresent(tenant -> {
            var hosts = trusted(tenant);
            policy.trust(hosts);
            policy.removeClientsOutside(hosts);
        });
    }

    /** Keycloak's policy is realm-wide, so only the operating Tenant's choice reaches it. */
    private void project(TenantId tenant) {
        if (tenants.operatingTenant().filter(tenant::equals).isEmpty()) return;
        var hosts = trusted(tenant);
        policy.trust(hosts);
        policy.removeClientsOutside(hosts);
    }

    private McpClientPolicy.Hosts trusted(TenantId tenant) {
        var clientId = new LinkedHashSet<String>();
        var document = new LinkedHashSet<String>();
        var builtIns = EnumSet.copyOf(McpTrustedApp.Preset.builtIns());
        for (var app : repository.list(tenant)) {
            if (app.builtIn()) {
                if (!app.enabled()) builtIns.remove(app.preset());
            } else if (app.enabled()) {
                clientId.addAll(app.clientIdHosts());
                document.addAll(app.documentHosts());
            }
        }
        for (var preset : builtIns) {
            clientId.addAll(preset.clientIdHosts());
            document.addAll(preset.documentHosts());
        }
        return new McpClientPolicy.Hosts(clientId, document);
    }

    private void requireManageable() {
        if (!policy.configured()) throw McpException.trustedAppsNotManageable();
    }

    private McpException missingOrChanged(TenantId tenant, UUID id) {
        return repository.find(tenant, id).isPresent() ? McpException.trustedAppChanged() : McpException.trustedAppNotFound();
    }

    private void record(ActorId actor, TenantId tenant, McpTrustedApp app, String change) {
        audit.record(AuditRecord.of(AuditAction.MCP_TRUSTED_APP_CHANGE, tenant).actor(actor)
                .resource("MCP_TRUSTED_APP", app.id(), app.name())
                .detail("change", change).detail("enabled", app.enabled())
                .detail("hosts", String.join(", ", app.clientIdHosts())).build());
    }

    /** A built-in app shows the hosts its preset carries. */
    private static McpTrustedApp resolved(McpTrustedApp app) {
        if (!app.builtIn()) return app;
        return new McpTrustedApp(app.id(), app.preset(), app.preset().displayName(), app.preset().clientIdHosts(),
                app.preset().documentHosts(), app.enabled(), app.revision());
    }

    /** Lower-case hosts; a loopback host is allowed only where a document lists its callback. */
    private static List<String> hosts(@Nullable List<String> values, boolean loopback) {
        var hosts = new LinkedHashSet<String>();
        if (values == null) return List.of();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            String host = value.strip().toLowerCase(Locale.ROOT);
            if (!HOST.matcher(host).matches() && !(loopback && LOOPBACK.contains(host))) {
                throw McpException.invalid("\"" + value.strip() + "\" is not a domain such as agent.example.com.");
            }
            hosts.add(host);
        }
        return new ArrayList<>(hosts);
    }
}
