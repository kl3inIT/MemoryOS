package io.memoryos.chat.catalog;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.execution.ChatExecutionProperties;
import io.memoryos.chat.execution.ChatModelBinding;
import io.memoryos.iam.identity.ActorId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolve product configuration first, then acquire native clients outside the DB transaction. */
public final class ChatModelResolver {
    private static final Logger LOG = LoggerFactory.getLogger(ChatModelResolver.class);
    private final ModelCatalogService catalog;
    private final ChatProviderAdapters adapters;
    private final ProviderCredentials credentials;
    private final ChatModelClients clients;
    private final ChatExecutionProperties limits;
    public ChatModelResolver(ModelCatalogService catalog, ChatProviderAdapters adapters, ProviderCredentials credentials,
                             ChatModelClients clients, ChatExecutionProperties limits) {
        this.catalog = catalog;
        this.adapters = adapters;
        this.credentials = credentials;
        this.clients = clients;
        this.limits = limits;
    }
    public Resolved resolve(ActorId actor, UUID session, @Nullable UUID requested) {
        return acquire(catalog.resolve(actor, session, requested));
    }
    /** The Tenant model for this flow, or the conversation model when the flow has none that is usable. */
    public Resolved resolveFlow(ActorId actor, UUID session, ModelFlow flow) {
        return acquire(catalog.resolveFlow(actor, session, flow));
    }
    public Resolved forValidation(ActorId actor, UUID model) { return acquire(catalog.validationSelection(actor, model)); }

    /**
     * Model names the provider endpoint reports, so an administrator selects real models instead of
     * typing them. The stored credential is read in its own transaction; the provider call follows it.
     */
    public java.util.List<String> reportedModels(ActorId actor, UUID providerId) {
        var connection = catalog.providerConnection(actor, providerId);
        var adapter = adapters.require(connection.adapterType());
        try {
            return adapter.reportedModels(
                            new ChatProviderAdapter.Connection(connection.baseUrl(), connection.credential()),
                            limits.providerReadTimeout())
                    .stream().filter(name -> !name.isBlank() && name.length() <= 200)
                    .distinct().sorted().limit(500).toList();
        } catch (ChatException expected) { throw expected; }
        catch (RuntimeException failure) {
            LOG.warn("Provider {} model listing failed ({})", providerId, failure.getClass().getSimpleName());
            throw ChatException.providerUnavailable();
        }
    }

    private Resolved acquire(ModelCatalogService.Selection selection) {
        var model = selection.model();
        var provider = selection.provider();
        if (model.settings().pricing() == null && limits.costCapped())
            throw ChatException.invalid("A cost budget requires configured model pricing.");
        var lease = clients.acquire(model.id(), provider.revision() + ":" + model.revision(), () -> {
            var adapter = adapters.require(provider.adapterType());
            var key = credentials.resolve(provider.tenantId(), provider.id(), provider.credential());
            if (adapter.credentialRequirement() == ChatProviderAdapter.CredentialRequirement.REQUIRED && key.isBlank())
                throw ChatException.providerUnavailable();
            try {
                return adapter.create(new ChatProviderAdapter.Connection(provider.baseUrl(), key), model.modelName(), model.settings(), limits.providerReadTimeout());
            } catch (ChatException expected) { throw expected; }
            catch (RuntimeException failure) {
                LOG.warn("Chat model {} client initialization failed ({})", model.id(), failure.getClass().getSimpleName());
                throw ChatException.providerUnavailable();
            }
        });
        if (!lease.binding().service().getName().equals(model.modelName())
                || (limits.costCapped() && lease.binding().service().getPricingModel() == null)) {
            lease.close();
            throw ChatException.providerUnavailable();
        }
        return new Resolved(model.id(), selection.fallbackReason(), lease, selection.contextRevision(),
                new Provenance(provider.id(), provider.name(), provider.dataBoundary().name()));
    }
    /** The catalog provider behind a resolved model, recorded with its usage. */
    public record Provenance(@Nullable UUID providerId, String providerName, @Nullable String dataBoundary) {
        public static final Provenance UNKNOWN = new Provenance(null, "unknown", null);
    }

    public record Resolved(UUID modelConfigurationId, @Nullable String fallbackReason, ChatModelClients.Lease lease,
                           @Nullable String contextRevision, Provenance provenance) implements AutoCloseable {
        public Resolved(UUID modelConfigurationId, @Nullable String fallbackReason, ChatModelClients.Lease lease) {
            this(modelConfigurationId, fallbackReason, lease, null, Provenance.UNKNOWN);
        }
        public Resolved(UUID modelConfigurationId, @Nullable String fallbackReason, ChatModelClients.Lease lease,
                        @Nullable String contextRevision) {
            this(modelConfigurationId, fallbackReason, lease, contextRevision, Provenance.UNKNOWN);
        }
        public ChatModelBinding binding() { return lease.binding(); }
        @Override public void close() { lease.close(); }
    }
}
