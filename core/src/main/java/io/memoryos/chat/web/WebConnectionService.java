package io.memoryos.chat.web;

import org.springframework.modulith.NamedInterface;
import io.memoryos.shared.TenantId;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.chat.ChatException;
import io.memoryos.ai.ModelCatalogService;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.chat.web.persistence.WebConnectionEntity;
import io.memoryos.chat.web.persistence.WebConnectionRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantAccessResolver;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authorized configuration only. Network calls happen after these transactions return. */
@Service
@NamedInterface("web")
public class WebConnectionService {
    private final WebConnectionRepository connections;
    private final ProviderCredentials credentials;
    private final IamAuthorization authorization;
    private final TenantAccessResolver tenants;
    private final AuditTrail audit;

    public WebConnectionService(WebConnectionRepository connections, ProviderCredentials credentials,
                                IamAuthorization authorization, TenantAccessResolver tenants,
                                AuditTrail audit) {
        this.audit = audit;
        this.connections = connections; this.credentials = credentials;
        this.authorization = authorization; this.tenants = tenants;
    }
    public record View(WebProvider provider, String endpoint, String engineId, boolean credentialConfigured,
                       boolean searchActive, boolean contentActive, long revision) {}
    public record Input(String endpoint, String engineId, ProviderCredentials.Change credential, long revision) {
        @Override public @NonNull String toString() { return "WebConnectionInput[redacted]"; }
    }
    public record Connection(UUID id, UUID tenantId, WebProvider provider, String endpoint, String engineId,
                             @Nullable String encryptedCredential, long revision) {
        @Override public @NonNull String toString() { return "WebConnection[redacted]"; }
    }
    public record Access(@Nullable Connection search, @Nullable Connection content) {}

    @Transactional(readOnly = true)
    public List<View> list(ActorId actor) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        return connections.findByTenantIdOrderByProvider(tenant).stream().map(this::view).toList();
    }

    @Transactional
    public View save(ActorId actor, WebProvider provider, Input input) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        if (input == null || input.endpoint() == null || input.engineId() == null || input.engineId().length() > 200)
            throw ChatException.invalid("Invalid Web connection.");
        if (provider.requiresEndpoint() && input.endpoint().isEmpty())
            throw ChatException.invalid("This provider requires its own endpoint.");
        if (!input.endpoint().isEmpty()) ModelCatalogService.validateEndpoint(input.endpoint());
        if (provider.requiresEngine() && input.engineId().isBlank()) throw ChatException.invalid("Search engine identity is required.");
        if (!provider.requiresEngine() && !input.engineId().isEmpty()) throw ChatException.invalid("This provider does not use a search engine identity.");
        var entity = connections.findByTenantIdAndProvider(tenant, provider).orElseGet(() -> new WebConnectionEntity(tenant, provider));
        if (entity.revision() != input.revision()) throw ChatException.conflict();
        String credential = credentials.update(tenant, entity.id(), entity.credential(), input.credential());
        entity.configure(input.endpoint(), input.engineId(), credential);
        if (provider.requiresKey() && !credentials.configured(credential)) {
            entity.selectSearch(false); entity.selectContent(false);
        }
        var saved = connections.saveAndFlush(entity);
        audit.record(AuditRecord.of(AuditAction.WEB_CONNECTION_CHANGE, new TenantId(tenant)).actor(actor).resource("WEB_CONNECTION", provider.name(), provider.name()).detail("change", "CONFIGURE").detail("credentialChange", input.credential() == null ? "KEEP" : input.credential().action().name()).build());
        return view(saved);
    }

    /** Null selection disables search or restores the built-in content reader. */
    @Transactional
    public void select(ActorId actor, boolean search, @Nullable WebProvider provider) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        var all = connections.findByTenantIdOrderByProvider(tenant);
        WebConnectionEntity selected = null;
        if (provider != null) {
            selected = all.stream().filter(c -> c.provider() == provider).findFirst().orElseThrow(ChatException::unavailable);
            if (!(search ? provider.search() : provider.content()) || !usable(selected)) throw ChatException.providerUnavailable();
        }
        for (var connection : all) {
            if (search) connection.selectSearch(false); else connection.selectContent(false);
        }
        connections.flush(); // Clear the old partial-unique-index winner before selecting another.
        if (selected != null) { if (search) selected.selectSearch(true); else selected.selectContent(true); }
        String role = search ? "SEARCH" : "CONTENT";
        audit.record(AuditRecord.of(AuditAction.WEB_CONNECTION_CHANGE, new TenantId(tenant)).actor(actor).resource("WEB_CONNECTION", provider == null ? null : provider.name(), provider == null ? null : provider.name()).detail("change", (provider == null ? "DISABLE_" : "SELECT_") + role).build());
    }

    @Transactional(readOnly = true)
    public Connection forTest(ActorId actor, WebProvider provider) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        var connection = connections.findByTenantIdAndProvider(tenant, provider).orElseThrow(ChatException::unavailable);
        if (!usable(connection)) throw ChatException.providerUnavailable();
        return snapshot(connection);
    }

    @Transactional(readOnly = true)
    public Access resolve(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        var all = connections.findByTenantIdOrderByProvider(tenant);
        return new Access(all.stream().filter(c -> c.searchActive() && usable(c)).findFirst().map(this::snapshot).orElse(null),
                all.stream().filter(c -> c.contentActive() && usable(c)).findFirst().map(this::snapshot).orElse(null));
    }
    /**
     * The key for listing a gateway's engines while its connection is being edited: the typed key, else the saved one
     * when the endpoint is unchanged (Onyx {@code api_key_changed}), so a saved key never reaches another host.
     */
    @Transactional(readOnly = true)
    public String discoveryKey(ActorId actor, WebProvider provider, String endpoint, @Nullable String typedKey) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        ModelCatalogService.validateEndpoint(endpoint);
        if (typedKey != null && !typedKey.isBlank()) return typedKey;
        var saved = connections.findByTenantIdAndProvider(tenant, provider)
                .filter(c -> c.endpoint().replaceAll("/+$", "").equals(endpoint.replaceAll("/+$", "")))
                .filter(c -> credentials.configured(c.credential()))
                .orElseThrow(() -> ChatException.invalid("Enter the API key."));
        return credentials.resolve(tenant, saved.id(), saved.credential());
    }

    public String key(Connection connection) {
        return credentials.resolve(connection.tenantId(), connection.id(), connection.encryptedCredential());
    }
    private boolean usable(WebConnectionEntity c) { return !c.provider().requiresKey() || credentials.configured(c.credential()); }
    private View view(WebConnectionEntity c) { return new View(c.provider(), c.endpoint(), c.engineId(), credentials.configured(c.credential()), c.searchActive(), c.contentActive(), c.revision()); }
    private Connection snapshot(WebConnectionEntity c) { return new Connection(c.id(), c.tenantId(), c.provider(), c.endpoint(), c.engineId(), c.credential(), c.revision()); }
}
