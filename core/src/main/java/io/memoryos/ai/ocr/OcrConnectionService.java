package io.memoryos.ai.ocr;

import io.memoryos.ai.AiException;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ProviderConnections;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.ai.persistence.JpaOcrConnectionRepository;
import io.memoryos.ai.persistence.OcrConnectionEntity;
import io.memoryos.audit.AuditAction;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorized configuration of the services that read scans and images, and the reader the extraction pipeline asks
 * for. Network calls happen after these transactions return.
 */
@Service
public class OcrConnectionService {
    private final JpaOcrConnectionRepository connections;
    private final ProviderConnections admin;
    private final IamAuthorization authorization;

    public OcrConnectionService(JpaOcrConnectionRepository connections, ProviderConnections admin,
                                IamAuthorization authorization) {
        this.connections = connections; this.admin = admin; this.authorization = authorization;
    }

    public record View(OcrProvider provider, String endpoint, boolean credentialConfigured, DataBoundary dataBoundary,
                       boolean active, long revision) {}
    public record Input(String endpoint, ProviderCredentials.@Nullable Change credential, DataBoundary dataBoundary,
                        long revision) {
        @Override public @NonNull String toString() { return "OcrConnectionInput[redacted]"; }
    }
    /** A connection to call, read in a transaction and used outside it. */
    public record Connection(@Nullable UUID id, UUID tenantId, OcrProvider provider, String endpoint,
                             @Nullable String encryptedCredential, DataBoundary dataBoundary, long revision) {
        @Override public @NonNull String toString() { return "OcrConnection[redacted]"; }
    }
    /** Unsaved connection details to test; a missing credentialValue falls back to the stored key. */
    public record ProbeInput(String endpoint, @Nullable String credentialValue) {
        @Override public @NonNull String toString() { return "OcrProbeInput[redacted]"; }
    }
    /** A validated connection to test; credential is the supplied plaintext key, null when the stored key applies. */
    public record Probe(Connection connection, @Nullable String credential) {
        @Override public @NonNull String toString() { return "OcrConnectionProbe[redacted]"; }
    }

    /** Installed protocols; model managers only. */
    @Transactional(readOnly = true)
    public List<OcrProvider> providers(ActorId actor) {
        authorization.require(actor, IamCapability.MODELS_MANAGE, false);
        return List.of(OcrProvider.values());
    }

    @Transactional(readOnly = true)
    public List<View> list(ActorId actor) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        return connections.findByTenantIdOrderByProvider(tenant).stream().map(this::view).toList();
    }

    @Transactional
    public View save(ActorId actor, OcrProvider provider, Input input) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        if (input == null || input.endpoint() == null || input.dataBoundary() == null)
            throw AiException.invalid("Invalid OCR connection.");
        var endpoint = endpoint(input.endpoint());
        var entity = connections.findByTenantIdAndProvider(tenant, provider)
                .orElseGet(() -> new OcrConnectionEntity(tenant, provider));
        String credential = admin.reconfigure(tenant, entity.id(), entity.revision(), input.revision(),
                entity.credential(), input.credential(), AiException::conflict);
        // The reader keeps a key its type requires; removing it would stop reading scans unseen.
        if (entity.active() && !admin.usable(provider.requiresKey(), credential))
            throw AiException.invalid("The OCR connection in use requires its key.");
        entity.configure(endpoint, credential, input.dataBoundary());
        var saved = connections.saveAndFlush(entity);
        audit(tenant, actor, provider, "CONFIGURE", ProviderConnections.credentialChange(input.credential()));
        return view(saved);
    }

    /** The reader of scans and images; null returns them to Docling's own OCR. */
    @Transactional
    public void select(ActorId actor, @Nullable OcrProvider provider) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        var all = connections.findByTenantIdOrderByProvider(tenant);
        OcrConnectionEntity selected = null;
        if (provider != null) {
            selected = all.stream().filter(c -> c.provider() == provider).findFirst()
                    .orElseThrow(AiException::unavailable);
            if (!usable(selected)) throw AiException.providerUnavailable();
        }
        ProviderConnections.selectOnly(all, selected, OcrConnectionEntity::select, connections::flush);
        audit(tenant, actor, provider, provider == null ? "DISABLE" : "SELECT", null);
    }

    /**
     * Connection to test; a null input tests the saved connection, otherwise the supplied endpoint is validated like a
     * save but never persisted, and a supplied credential replaces the stored key.
     */
    @Transactional(readOnly = true)
    public Probe forTest(ActorId actor, OcrProvider provider, @Nullable ProbeInput input) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        var stored = connections.findByTenantIdAndProvider(tenant, provider).orElse(null);
        if (input == null) {
            if (stored == null || !usable(stored)) throw AiException.providerUnavailable();
            return new Probe(snapshot(stored), null);
        }
        if (input.endpoint() == null) throw AiException.invalid("Invalid OCR connection.");
        var endpoint = endpoint(input.endpoint());
        String key = input.credentialValue();
        boolean override = key != null && !key.isBlank();
        if (override && key.length() > 8192) throw AiException.invalid("Invalid provider credential.");
        if (!override && provider.requiresKey() && (stored == null || !admin.configured(stored.credential())))
            throw AiException.invalid("An API key is required to test this provider.");
        var connection = new Connection(stored == null ? null : stored.id(), tenant, provider, endpoint,
                override || stored == null ? null : stored.credential(),
                stored == null ? provider.dataBoundary() : stored.dataBoundary(), stored == null ? 0 : stored.revision());
        return new Probe(connection, override ? key.strip() : null);
    }

    /** The connection that reads a Tenant's scans and images, or null when Docling reads them with its own OCR. */
    @Transactional(readOnly = true)
    public @Nullable Connection reader(TenantId tenant) {
        return connections.findByTenantIdAndActiveTrue(tenant.value()).filter(this::usable).map(this::snapshot)
                .orElse(null);
    }

    /** The plaintext key of a connection, empty when it has none. */
    public String key(Connection connection) {
        return admin.key(connection.tenantId(), connection.id(), connection.encryptedCredential());
    }

    private static String endpoint(String input) {
        var endpoint = input.strip();
        ProviderConnections.checkEndpoint(endpoint, true,
                () -> AiException.invalid("This OCR provider requires its endpoint."));
        return endpoint;
    }
    private void audit(UUID tenant, ActorId actor, @Nullable OcrProvider provider, String change,
                       @Nullable String credential) {
        admin.audit(AuditAction.OCR_CONNECTION_CHANGE, "OCR_CONNECTION", tenant, actor,
                provider == null ? null : provider.name(), change, credential);
    }
    private boolean usable(OcrConnectionEntity c) { return admin.usable(c.provider().requiresKey(), c.credential()); }
    private View view(OcrConnectionEntity c) {
        return new View(c.provider(), c.endpoint(), admin.configured(c.credential()), c.dataBoundary(), c.active(),
                c.revision());
    }
    private Connection snapshot(OcrConnectionEntity c) {
        return new Connection(c.id(), c.tenantId(), c.provider(), c.endpoint(), c.credential(), c.dataBoundary(),
                c.revision());
    }
}
