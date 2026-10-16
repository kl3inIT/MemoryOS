package io.memoryos.chat.image;

import org.springframework.modulith.NamedInterface;

import io.memoryos.audit.AuditAction;
import io.memoryos.chat.ChatException;
import io.memoryos.ai.ProviderConnections;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.chat.image.persistence.ImageConnectionEntity;
import io.memoryos.chat.image.persistence.ImageConnectionRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantAccessResolver;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authorized configuration only. Network calls happen after these transactions return. */
@Service
@NamedInterface("image")
public class ImageConnectionService {
    private final ImageConnectionRepository connections;
    private final ProviderConnections admin;
    private final IamAuthorization authorization;
    private final TenantAccessResolver tenants;
    private final ImageAdapterRegistry adapters;

    public ImageConnectionService(ImageConnectionRepository connections, ProviderConnections admin,
                                  IamAuthorization authorization, TenantAccessResolver tenants, ImageAdapterRegistry adapters) {
        this.connections = connections; this.admin = admin;
        this.authorization = authorization; this.tenants = tenants; this.adapters = adapters;
    }
    public record View(ImageProvider provider, String endpoint, String model, @Nullable ImageTemplate template,
                       boolean credentialConfigured, boolean active, long revision) {}
    /** @param template required for {@link ImageProvider#CUSTOM_HTTP}, refused for every other provider */
    public record Input(String endpoint, String model, @Nullable ImageTemplate template, ProviderCredentials.Change credential,
                        long revision) {
        @Override public @NonNull String toString() { return "ImageConnectionInput[redacted]"; }
    }
    public record Connection(@Nullable UUID id, UUID tenantId, ImageProvider provider, String endpoint, String model,
                             @Nullable ImageTemplate template, @Nullable String encryptedCredential, long revision) {
        @Override public @NonNull String toString() { return "ImageConnection[redacted]"; }
    }
    /** Unsaved connection details to probe; a missing credentialValue falls back to the stored key. */
    public record ProbeInput(String endpoint, String model, @Nullable ImageTemplate template, @Nullable String credentialValue) {
        @Override public @NonNull String toString() { return "ImageProbeInput[redacted]"; }
    }
    /** A validated connection to test; credential is the supplied plaintext key, null when the stored key applies. */
    public record Probe(Connection connection, @Nullable String credential) {
        @Override public @NonNull String toString() { return "ImageConnectionProbe[redacted]"; }
    }
    public record Access(@Nullable Connection generate) {}
    /** An installed protocol and what its adapter needs and serves. */
    public record Installed(ImageProvider provider, ImageProviderCapabilities capabilities) {}

    /** Installed protocols and their published models; model managers only. */
    @Transactional(readOnly = true)
    public List<Installed> providers(ActorId actor) {
        authorization.require(actor, IamCapability.MODELS_MANAGE, false);
        return Arrays.stream(ImageProvider.values()).map(p -> new Installed(p, adapters.capabilities(p))).toList();
    }

    @Transactional(readOnly = true)
    public List<View> list(ActorId actor) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        return connections.findByTenantIdOrderByProvider(tenant).stream().map(this::view).toList();
    }

    @Transactional
    public View save(ActorId actor, ImageProvider provider, Input input) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        if (input == null || input.endpoint() == null || input.model() == null || input.model().length() > 200)
            throw ChatException.invalid("Invalid image connection.");
        if (input.model().isBlank()) throw ChatException.invalid("An image model is required.");
        var endpoint = endpoint(provider, input.endpoint());
        String template = template(provider, input.template());
        var entity = connections.findByTenantIdAndProvider(tenant, provider).orElseGet(() -> new ImageConnectionEntity(tenant, provider));
        String credential = admin.reconfigure(tenant, entity.id(), entity.revision(), input.revision(),
                entity.credential(), input.credential(), ChatException::conflict);
        entity.configure(endpoint, input.model(), credential, template);
        if (!admin.usable(adapters.capabilities(provider).requiresKey(), credential)) entity.select(false);
        var saved = connections.saveAndFlush(entity);
        audit(tenant, actor, provider, "CONFIGURE", ProviderConnections.credentialChange(input.credential()));
        return view(saved);
    }

    /** Null selection disables image generation for the Tenant. */
    @Transactional
    public void select(ActorId actor, @Nullable ImageProvider provider) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        var all = connections.findByTenantIdOrderByProvider(tenant);
        ImageConnectionEntity selected = null;
        if (provider != null) {
            selected = all.stream().filter(c -> c.provider() == provider).findFirst().orElseThrow(ChatException::unavailable);
            if (!usable(selected)) throw ChatException.providerUnavailable();
        }
        ProviderConnections.selectOnly(all, selected, ImageConnectionEntity::select, connections::flush);
        audit(tenant, actor, provider, provider == null ? "DISABLE" : "SELECT", null);
    }

    /**
     * Connection to test; a null input tests the saved connection, otherwise the supplied endpoint/model
     * are validated like a save but never persisted, and a supplied credential replaces the stored key.
     */
    @Transactional(readOnly = true)
    public Probe forTest(ActorId actor, ImageProvider provider, @Nullable ProbeInput input) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        var stored = connections.findByTenantIdAndProvider(tenant, provider).orElse(null);
        if (input == null) {
            if (stored == null || !usable(stored)) throw ChatException.providerUnavailable();
            return new Probe(snapshot(stored), null);
        }
        if (input.endpoint() == null || input.model() == null || input.model().isBlank() || input.model().length() > 200)
            throw ChatException.invalid("Invalid image connection.");
        var endpoint = endpoint(provider, input.endpoint());
        template(provider, input.template());
        String key = input.credentialValue();
        boolean override = key != null && !key.isBlank();
        if (override && key.length() > 8192) throw ChatException.invalid("Invalid provider credential.");
        if (!override && adapters.capabilities(provider).requiresKey() && (stored == null || !admin.configured(stored.credential())))
            throw ChatException.invalid("An API key is required to test this provider.");
        var connection = new Connection(stored == null ? null : stored.id(), tenant, provider, endpoint,
                input.model(), input.template(), override || stored == null ? null : stored.credential(), stored == null ? 0 : stored.revision());
        return new Probe(connection, override ? key.trim() : null);
    }

    @Transactional(readOnly = true)
    public Access resolve(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        var all = connections.findByTenantIdOrderByProvider(tenant);
        return new Access(all.stream().filter(c -> c.active() && usable(c)).findFirst().map(this::snapshot).orElse(null));
    }
    public String key(Connection connection) {
        return admin.key(connection.tenantId(), connection.id(), connection.encryptedCredential());
    }
    private String endpoint(ImageProvider provider, String input) {
        var endpoint = adapters.normalizeEndpoint(provider, input);
        ProviderConnections.checkEndpoint(endpoint, adapters.capabilities(provider).endpointRequired(),
                () -> ChatException.invalid("This image provider requires an endpoint."));
        return endpoint;
    }
    /** The template to store: a valid one for a custom HTTP connection, none for any other. */
    private static @Nullable String template(ImageProvider provider, @Nullable ImageTemplate template) {
        if (provider != ImageProvider.CUSTOM_HTTP) {
            if (template != null) throw ChatException.invalid("Only a custom HTTP image connection takes a request template.");
            return null;
        }
        if (template == null) throw ChatException.invalid("A custom HTTP image connection requires a request template.");
        try { template.validate(); }
        catch (IllegalArgumentException invalid) { throw ChatException.invalid("Invalid image request template."); }
        return template.toJson();
    }
    private static @Nullable ImageTemplate stored(@Nullable String template) {
        return template == null ? null : ImageTemplate.fromJson(template);
    }
    private void audit(UUID tenant, ActorId actor, @Nullable ImageProvider provider, String change, @Nullable String credential) {
        admin.audit(AuditAction.IMAGE_CONNECTION_CHANGE, "IMAGE_CONNECTION", tenant, actor,
                provider == null ? null : provider.name(), change, credential);
    }
    private boolean usable(ImageConnectionEntity c) { return admin.usable(adapters.capabilities(c.provider()).requiresKey(), c.credential()); }
    private View view(ImageConnectionEntity c) { return new View(c.provider(), c.endpoint(), c.model(), stored(c.template()), admin.configured(c.credential()), c.active(), c.revision()); }
    private Connection snapshot(ImageConnectionEntity c) { return new Connection(c.id(), c.tenantId(), c.provider(), c.endpoint(), c.model(), stored(c.template()), c.credential(), c.revision()); }
}
