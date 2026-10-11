package io.memoryos.ai;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Leases the native client of a catalog model: the one place a provider adapter is asked for it, whether a Chat turn or
 * the Worker's OCR reads with the model. Call outside any transaction.
 */
public final class ModelLeases {
    private static final Logger LOG = LoggerFactory.getLogger(ModelLeases.class);
    private final ProviderAdapterRegistry adapters;
    private final ProviderCredentials credentials;
    private final ModelClients clients;
    private final Duration providerReadTimeout;

    /** {@code providerReadTimeout} bounds connecting and each read gap of a provider call. */
    public ModelLeases(ProviderAdapterRegistry adapters, ProviderCredentials credentials, ModelClients clients,
                       Duration providerReadTimeout) {
        this.adapters = adapters;
        this.credentials = credentials;
        this.clients = clients;
        this.providerReadTimeout = providerReadTimeout;
    }

    /** The model's client, kept while the provider and model revisions stand. */
    public ModelClients.Lease lease(LlmProvider provider, ModelConfiguration model) {
        var lease = clients.acquire(model.id(), provider.revision() + ":" + model.revision(), () -> {
            var adapter = adapters.require(provider.adapterType());
            var key = credentials.resolve(provider.tenantId(), provider.id(), provider.credential());
            if (adapter.credentialRequirement() == ProviderAdapter.CredentialRequirement.REQUIRED && key.isBlank())
                throw AiException.providerUnavailable();
            try {
                return adapter.create(new ProviderAdapter.Connection(provider.baseUrl(), key), model.modelName(), model.settings(), providerReadTimeout);
            } catch (AiException expected) { throw expected; }
            catch (RuntimeException failure) {
                LOG.atWarn().addKeyValue("event", "ai.model.client_initialization_failed")
                        .addKeyValue("model_configuration_id", model.id())
                        .addKeyValue("error_type", failure.getClass().getName()).log("Chat model client initialization failed");
                throw AiException.providerUnavailable();
            }
        });
        if (!lease.binding().service().getName().equals(model.modelName())) {
            lease.close();
            throw AiException.providerUnavailable();
        }
        return lease;
    }
}
