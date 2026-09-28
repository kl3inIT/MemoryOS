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

    public ImageConnectionService(ImageConnectionRepository connections, ProviderConnections admin,
                                  IamAuthorization authorization, TenantAccessResolver tenants) {
        this.connections = connections; this.admin = admin;
        this.authorization = authorization; this.tenants = tenants;
    }
    public record View(ImageProvider provider, String endpoint, String model, boolean credentialConfigured,
                       boolean active, long revision) {}
    public record Input(String endpoint, String model, ProviderCredentials.Change credential, long revision) {
        @Override public @NonNull String toString() { return "ImageConnectionInput[redacted]"; }
    }
    public record Connection(@Nullable UUID id, UUID tenantId, ImageProvider provider, String endpoint, String model,
                             @Nullable String encryptedCredential, long revision) {
        @Override public @NonNull String toString() { return "ImageConnection[redacted]"; }
    }
    /** Unsaved connection details to probe; a missing credentialValue falls back to the stored key. */
    public record ProbeInput(String endpoint, String model, @Nullable String credentialValue) {
        @Override public @NonNull String toString() { return "ImageProbeInput[redacted]"; }
    }
    /** A validated connection to test; credential is the supplied plaintext key, null when the stored key applies. */
    public record Probe(Connection connection, @Nullable String credential) {
        @Override public @NonNull String toString() { return "ImageConnectionProbe[redacted]"; }
    }
    public record Access(@Nullable Connection generate) {}

    /** Installed protocols and their published models; model managers only. */
    @Transactional(readOnly = true)
    public List<ImageProvider> providers(ActorId actor) {
        authorization.require(actor, IamCapability.MODELS_MANAGE, false);
        return List.of(ImageProvider.values());
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
        var entity = connections.findByTenantIdAndProvider(tenant, provider).orElseGet(() -> new ImageConnectionEntity(tenant, provider));
        String credential = admin.reconfigure(tenant, entity.id(), entity.revision(), input.revision(),
                entity.credential(), input.credential(), ChatException::conflict);
        entity.configure(endpoint, input.model(), credential);
        if (!admin.usable(provider.requiresKey(), credential)) entity.select(false);
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
        String key = input.credentialValue();
        boolean override = key != null && !key.isBlank();
        if (override && key.length() > 8192) throw ChatException.invalid("Invalid provider credential.");
        if (!override && provider.requiresKey() && (stored == null || !admin.configured(stored.credential())))
            throw ChatException.invalid("An API key is required to test this provider.");
        var connection = new Connection(stored == null ? null : stored.id(), tenant, provider, endpoint,
                input.model(), override || stored == null ? null : stored.credential(), stored == null ? 0 : stored.revision());
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
    private static String endpoint(ImageProvider provider, String input) {
        var endpoint = provider.normalizeEndpoint(input);
        ProviderConnections.checkEndpoint(endpoint, provider.endpointRequired(),
                () -> ChatException.invalid("This image provider requires an endpoint."));
        return endpoint;
    }
    private void audit(UUID tenant, ActorId actor, @Nullable ImageProvider provider, String change, @Nullable String credential) {
        admin.audit(AuditAction.IMAGE_CONNECTION_CHANGE, "IMAGE_CONNECTION", tenant, actor,
                provider == null ? null : provider.name(), change, credential);
    }
    private boolean usable(ImageConnectionEntity c) { return admin.usable(c.provider().requiresKey(), c.credential()); }
    private View view(ImageConnectionEntity c) { return new View(c.provider(), c.endpoint(), c.model(), admin.configured(c.credential()), c.active(), c.revision()); }
    private Connection snapshot(ImageConnectionEntity c) { return new Connection(c.id(), c.tenantId(), c.provider(), c.endpoint(), c.model(), c.credential(), c.revision()); }
}
