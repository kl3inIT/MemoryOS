package io.memoryos.ai.systemone;

import io.memoryos.ai.AiException;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ModelFlow;
import io.memoryos.ai.ProviderConnections;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.ai.persistence.JpaSystemOneConnectionRepository;
import io.memoryos.ai.persistence.ModelCatalogRepository;
import io.memoryos.ai.persistence.SystemOneConnectionEntity;
import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authorized configuration only. Network calls happen after these transactions return. */
@Service
public class SystemOneConnectionService {
    static final int MAX_CONNECTIONS = 32;
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_MODEL_LENGTH = 200;
    private static final double MAX_PRICE = 1_000_000;
    /** A Cloudflare account identifier. */
    private static final Pattern ACCOUNT = Pattern.compile("[0-9a-f]{32}");

    private final JpaSystemOneConnectionRepository connections;
    private final ModelCatalogRepository catalog;
    private final ProviderConnections admin;
    private final IamAuthorization authorization;
    private final SystemOneAdapterRegistry adapters;
    private final AuditTrail audit;

    public SystemOneConnectionService(JpaSystemOneConnectionRepository connections, ModelCatalogRepository catalog,
                                      ProviderConnections admin, IamAuthorization authorization,
                                      SystemOneAdapterRegistry adapters, AuditTrail audit) {
        this.connections = connections; this.catalog = catalog; this.admin = admin;
        this.authorization = authorization; this.adapters = adapters; this.audit = audit;
    }

    public record Type(SystemOneProvider provider, SystemOneCapabilities capabilities) {}
    public record View(UUID id, SystemOneProvider provider, String name, String endpoint, String model,
                       boolean credentialConfigured, DataBoundary dataBoundary, @Nullable Double inputPrice,
                       long revision) {}
    public record Input(String name, String endpoint, String model, ProviderCredentials.Change credential,
                        DataBoundary dataBoundary, @Nullable Double inputPrice, long revision) {
        @Override public @NonNull String toString() { return "SystemOneConnectionInput[redacted]"; }
    }
    /** A connection to call, read in a transaction and used outside it. */
    public record Connection(UUID id, UUID tenantId, SystemOneProvider provider, String name, String endpoint,
                             String model, @Nullable String encryptedCredential, DataBoundary dataBoundary,
                             @Nullable Double inputPrice) {
        @Override public @NonNull String toString() { return "SystemOneConnection[redacted]"; }

        /** What a call cost, from the tokens it reported; null when the price or the count is unknown. */
        public @Nullable Double cost(@Nullable Long inputTokens) {
            return inputPrice == null || inputTokens == null ? null : inputTokens * inputPrice / 1_000_000;
        }
    }

    public List<Type> types() {
        return Arrays.stream(SystemOneProvider.values())
                .map(provider -> new Type(provider, adapters.capabilities(provider))).toList();
    }

    @Transactional(readOnly = true)
    public List<View> list(ActorId actor) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        return connections.findByTenantIdOrderByNameAscIdAsc(tenant).stream().map(this::view).toList();
    }

    @Transactional
    public View create(ActorId actor, SystemOneProvider provider, Input input) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        if (connections.countByTenantId(tenant) >= MAX_CONNECTIONS)
            throw AiException.invalid("At most 32 System One connections.");
        return save(tenant, actor, new SystemOneConnectionEntity(tenant, provider), input, "CREATE");
    }

    @Transactional
    public View update(ActorId actor, UUID id, Input input) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        var entity = connections.findByTenantIdAndId(tenant, id).orElseThrow(AiException::unavailable);
        return save(tenant, actor, entity, input, "CONFIGURE");
    }

    /** A connection a task runs on is not deleted; the task is moved first. */
    @Transactional
    public void delete(ActorId actor, UUID id) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        var entity = connections.findByTenantIdAndId(tenant, id).orElseThrow(AiException::unavailable);
        if (catalog.flowUsesClassifier(tenant, id)) throw AiException.conflict();
        connections.delete(entity);
        audit(tenant, actor, entity.name(), "DELETE", null);
    }

    /**
     * Runs a classifying task on a connection. The task's language model and reasoning level are cleared: a task has
     * one classifier.
     */
    @Transactional
    public void assign(ActorId actor, ModelFlow flow, UUID id, long revision) {
        var tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        if (!flow.classifies()) throw AiException.invalid("This task does not run on a System One connection.");
        var entity = connections.findByTenantIdAndId(tenant, id).orElseThrow(AiException::unavailable);
        if (!usable(entity)) throw AiException.providerUnavailable();
        var before = catalog.flowDefault(tenant, flow);
        catalog.setFlowClassifier(tenant, flow, id, revision);
        if (!Objects.equals(before.systemOneConnectionId(), id))
            audit.record(AuditRecord.of(AuditAction.MODEL_FLOW_CHANGE, new TenantId(tenant)).actor(actor)
                    .resource("MODEL_FLOW", flow.name(), null).detail("flow", flow.name())
                    .detail("before", classifier(tenant, before.systemOneConnectionId(), before.modelConfigurationId()))
                    .detail("after", classifier(tenant, id, null)).build());
    }

    @Transactional(readOnly = true)
    public Connection forTest(ActorId actor, UUID id) {
        var tenant = authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
        var entity = connections.findByTenantIdAndId(tenant, id).orElseThrow(AiException::unavailable);
        if (!usable(entity)) throw AiException.providerUnavailable();
        return snapshot(entity);
    }

    /**
     * The connection a task runs on, or null when it runs on a language model. It is returned whenever the task names
     * one: a connection that cannot answer fails its call, so the task never silently changes classifier and the
     * failure is counted against System One.
     */
    @Transactional(readOnly = true)
    public @Nullable Connection forFlow(TenantId tenant, ModelFlow flow) {
        if (!flow.classifies()) return null;
        UUID id = catalog.flowDefault(tenant.value(), flow).systemOneConnectionId();
        if (id == null) return null;
        return snapshot(connections.findByTenantIdAndId(tenant.value(), id).orElseThrow(AiException::providerUnavailable));
    }

    String key(Connection connection) {
        return admin.key(connection.tenantId(), connection.id(), connection.encryptedCredential());
    }

    private View save(UUID tenant, ActorId actor, SystemOneConnectionEntity entity, Input input, String change) {
        if (input == null || input.name() == null || input.endpoint() == null || input.model() == null
                || input.dataBoundary() == null)
            throw AiException.invalid("Invalid System One connection.");
        var capabilities = adapters.capabilities(entity.provider());
        String name = input.name().strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) throw AiException.invalid("A connection name is empty or too long.");
        if (connections.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenant, name, entity.id()))
            throw AiException.invalid("A connection with this name exists.");
        String endpoint = endpoint(capabilities.endpoint(), input.endpoint().strip());
        String model = input.model().isBlank() ? capabilities.defaultModel() : input.model().strip();
        if (model.isEmpty() || model.length() > MAX_MODEL_LENGTH) throw AiException.invalid("A model is required.");
        var price = input.inputPrice();
        if (price != null && (!Double.isFinite(price) || price < 0 || price > MAX_PRICE))
            throw AiException.invalid("Invalid input price.");
        String credential = admin.reconfigure(tenant, entity.id(), entity.revision(), input.revision(),
                entity.credential(), input.credential(), AiException::conflict);
        // A connection a task runs on keeps a key its type requires; removing it would stop the task unseen.
        if (!admin.usable(capabilities.requiresKey(), credential) && catalog.flowUsesClassifier(tenant, entity.id()))
            throw AiException.invalid("A connection in use requires its key.");
        entity.configure(name, endpoint, model, credential, input.dataBoundary(), price);
        var saved = connections.saveAndFlush(entity);
        audit(tenant, actor, name, change, ProviderConnections.credentialChange(input.credential()));
        return view(saved);
    }

    private static String endpoint(SystemOneCapabilities.Endpoint kind, String value) {
        switch (kind) {
            case FIXED -> {
                if (!value.isEmpty()) throw AiException.invalid("This connection type has a fixed address.");
            }
            case URL -> ProviderConnections.checkEndpoint(value, true,
                    () -> AiException.invalid("This connection type requires its endpoint."));
            case ACCOUNT -> {
                if (!ACCOUNT.matcher(value).matches()) throw AiException.invalid("An account ID is required.");
            }
        }
        return value;
    }

    /** What a task ran on, as the audit names it: the connection, else the model's id, else the conversation model. */
    private Map<String, Object> classifier(UUID tenant, @Nullable UUID connection, @Nullable UUID model) {
        var facts = new LinkedHashMap<String, Object>();
        if (connection != null) {
            var entity = connections.findByTenantIdAndId(tenant, connection).orElseThrow(AiException::unavailable);
            facts.put("systemOneConnection", entity.name());
            facts.put("provider", entity.provider().name());
            facts.put("model", entity.model());
            facts.put("dataBoundary", entity.dataBoundary().name());
        } else if (model != null) facts.put("modelConfigurationId", model.toString());
        return facts;
    }

    private void audit(UUID tenant, ActorId actor, String name, String change, @Nullable String credential) {
        admin.audit(AuditAction.SYSTEM_ONE_CONNECTION_CHANGE, "SYSTEM_ONE_CONNECTION", tenant, actor, name, change,
                credential);
    }
    private boolean usable(SystemOneConnectionEntity c) {
        return admin.usable(adapters.capabilities(c.provider()).requiresKey(), c.credential());
    }
    private View view(SystemOneConnectionEntity c) {
        return new View(c.id(), c.provider(), c.name(), c.endpoint(), c.model(), admin.configured(c.credential()),
                c.dataBoundary(), c.inputPrice(), c.revision());
    }
    private Connection snapshot(SystemOneConnectionEntity c) {
        return new Connection(c.id(), c.tenantId(), c.provider(), c.name(), c.endpoint(), c.model(), c.credential(),
                c.dataBoundary(), c.inputPrice());
    }
}
