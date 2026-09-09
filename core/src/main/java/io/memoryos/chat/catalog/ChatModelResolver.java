package io.memoryos.chat.catalog;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.execution.ChatExecutionProperties;
import io.memoryos.chat.execution.ChatModelBinding;
import io.memoryos.iam.ActorId;
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
    public Resolved forValidation(ActorId actor, UUID model) { return acquire(catalog.validationSelection(actor, model)); }

    private Resolved acquire(ModelCatalogService.Selection selection) {
        var model = selection.model();
        var provider = selection.provider();
        if (model.settings().pricing() == null && limits.costBudgetUsd() < Double.MAX_VALUE)
            throw ChatException.invalid("A cost budget requires configured model pricing.");
        var lease = clients.acquire(model.id(), provider.revision() + ":" + model.revision(), () -> {
            var adapter = adapters.require(provider.adapterType());
            var key = credentials.resolve(provider.tenantId(), provider.id(), provider.credential());
            if (adapter.credentialRequirement() == ChatProviderAdapter.CredentialRequirement.REQUIRED && key.isBlank())
                throw ChatException.providerUnavailable();
            try {
                return adapter.create(new ChatProviderAdapter.Connection(provider.baseUrl(), key), model.modelName(), model.settings(), limits.deadline());
            } catch (ChatException expected) { throw expected; }
            catch (RuntimeException failure) {
                LOG.warn("Chat model {} client initialization failed ({})", model.id(), failure.getClass().getSimpleName());
                throw ChatException.providerUnavailable();
            }
        });
        if (!lease.binding().service().getName().equals(model.modelName())
                || (limits.costBudgetUsd() < Double.MAX_VALUE && lease.binding().service().getPricingModel() == null)) {
            lease.close();
            throw ChatException.providerUnavailable();
        }
        return new Resolved(model.id(), selection.fallbackReason(), lease);
    }
    public record Resolved(UUID modelConfigurationId, @Nullable String fallbackReason, ChatModelClients.Lease lease) implements AutoCloseable {
        public ChatModelBinding binding() { return lease.binding(); }
        @Override public void close() { lease.close(); }
    }
}
