package io.memoryos.chat.image;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.catalog.ModelCatalogService;
import io.memoryos.chat.catalog.ProviderCredentials;
import io.memoryos.chat.persistence.ImageConnectionEntity;
import io.memoryos.chat.persistence.ImageConnectionRepository;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.tenant.TenantAccessResolver;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authorized configuration only. Network calls happen after these transactions return. */
@Service
public class ImageConnectionService {
    private final ImageConnectionRepository connections;
    private final ProviderCredentials credentials;
    private final IamAuthorization authorization;
    private final TenantAccessResolver tenants;

    public ImageConnectionService(ImageConnectionRepository connections, ProviderCredentials credentials,
                                  IamAuthorization authorization, TenantAccessResolver tenants) {
        this.connections = connections; this.credentials = credentials;
        this.authorization = authorization; this.tenants = tenants;
    }
    public record View(ImageProvider provider, String endpoint, String model, boolean credentialConfigured,
                       boolean active, long revision) {}
    public record Input(String endpoint, String model, ProviderCredentials.Change credential, long revision) {
        @Override public @NonNull String toString() { return "ImageConnectionInput[redacted]"; }
    }
    public record Connection(UUID id, UUID tenantId, ImageProvider provider, String endpoint, String model,
                             @Nullable String encryptedCredential, long revision) {
        @Override public @NonNull String toString() { return "ImageConnection[redacted]"; }
    }
    public record Access(@Nullable Connection generate) {}

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
        if (!input.endpoint().isEmpty()) ModelCatalogService.validateEndpoint(input.endpoint());
        var entity = connections.findByTenantIdAndProvider(tenant, provider).orElseGet(() -> new ImageConnectionEntity(tenant, provider));
        if (entity.revision() != input.revision()) throw ChatException.conflict();
        String credential = credentials.update(tenant, entity.id(), entity.credential(), input.credential());
        entity.configure(input.endpoint(), input.model(), credential);
        if (provider.requiresKey() && !credentials.configured(credential)) entity.select(false);
        return view(connections.saveAndFlush(entity));
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
        for (var connection : all) connection.select(false);
        connections.flush(); // Clear the old partial-unique-index winner before selecting another.
        if (selected != null) selected.select(true);
    }

    @Transactional(readOnly = true)
    public Connection forTest(ActorId actor, ImageProvider provider) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        var connection = connections.findByTenantIdAndProvider(tenant, provider).orElseThrow(ChatException::unavailable);
        if (!usable(connection)) throw ChatException.providerUnavailable();
        return snapshot(connection);
    }

    @Transactional(readOnly = true)
    public Access resolve(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable).value();
        var all = connections.findByTenantIdOrderByProvider(tenant);
        return new Access(all.stream().filter(c -> c.active() && usable(c)).findFirst().map(this::snapshot).orElse(null));
    }
    public String key(Connection connection) {
        return credentials.resolve(connection.tenantId(), connection.id(), connection.encryptedCredential());
    }
    private boolean usable(ImageConnectionEntity c) { return !c.provider().requiresKey() || credentials.configured(c.credential()); }
    private View view(ImageConnectionEntity c) { return new View(c.provider(), c.endpoint(), c.model(), credentials.configured(c.credential()), c.active(), c.revision()); }
    private Connection snapshot(ImageConnectionEntity c) { return new Connection(c.id(), c.tenantId(), c.provider(), c.endpoint(), c.model(), c.credential(), c.revision()); }
}
