package io.memoryos.ai;

import java.time.Duration;
import io.memoryos.shared.ActorId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Resolve product configuration first, then acquire native clients outside the DB transaction. */
public final class ModelResolver {
    private static final Logger LOG = LoggerFactory.getLogger(ModelResolver.class);
    private final ModelCatalogService catalog;
    private final ProviderAdapters adapters;
    private final ProviderCredentials credentials;
    private final ModelClients clients;
    private final Duration providerReadTimeout;
    private final boolean costCapped;

    /**
     * {@code providerReadTimeout} bounds connecting and each read gap of a provider call; {@code costCapped} says the
     * deployment caps spending, so every model must carry its pricing.
     */
    public ModelResolver(ModelCatalogService catalog, ProviderAdapters adapters, ProviderCredentials credentials,
                             ModelClients clients, Duration providerReadTimeout, boolean costCapped) {
        this.catalog = catalog;
        this.adapters = adapters;
        this.credentials = credentials;
        this.clients = clients;
        this.providerReadTimeout = providerReadTimeout;
        this.costCapped = costCapped;
    }
    /** The Tenant model for background work with no conversation behind it, such as a meeting's minutes. */
    public Resolved resolveFlow(ActorId actor, ModelFlow flow) {
        return acquire(catalog.resolveFlow(actor, flow));
    }
    public Resolved forValidation(ActorId actor, UUID model) { return acquire(catalog.validationSelection(actor, model)); }

    /**
     * A reported model with the specs to add it. As Onyx, every reported model can be added without typing: what
     * neither the endpoint nor the catalog publishes takes Onyx's defaults, and {@link Source#NONE} tells the
     * administrator the values are defaults to review.
     */
    public record ReportedModelSpec(String modelName, int contextWindow, @Nullable Integer maxOutputTokens,
                                    ModelSettings.Capabilities capabilities, ModelSettings.@Nullable Pricing pricing,
                                    Source source) {
        public enum Source { PROVIDER, CATALOG, NONE }
    }

    /**
     * Models the provider endpoint reports, so an administrator selects real models instead of typing their
     * specs, as Onyx's per-provider {@code available-models} fetchers do. Limits and capabilities the endpoint
     * publishes come first; the installed catalog fills the rest by name. The stored credential is read in its own
     * transaction; the provider call follows it.
     */
    public java.util.List<ReportedModelSpec> reportedModels(ActorId actor, UUID providerId) {
        return reportedModels(catalog.providerConnection(actor, providerId));
    }

    /**
     * The same listing for a connection the administrator is still editing, so the provider form fills its model list
     * before the provider exists, as Onyx's provider form does. The caller authorizes the connection.
     */
    public java.util.List<ReportedModelSpec> reportedModels(ModelCatalogService.ProviderConnection connection) {
        var adapter = adapters.require(connection.adapterType());
        try {
            var reported = adapter.reportedModels(
                            new ProviderAdapter.Connection(connection.baseUrl(), connection.credential()),
                            providerReadTimeout);
            var seen = new java.util.HashSet<String>();
            return reported.stream()
                    .filter(model -> !model.modelName().isBlank() && model.modelName().length() <= 200)
                    .filter(model -> seen.add(model.modelName()))
                    .sorted(java.util.Comparator.comparing(ProviderAdapter.ReportedModel::modelName))
                    .limit(1000)
                    .map(model -> spec(model, adapter.knownModels()))
                    .toList();
        } catch (AiException expected) { throw expected; }
        catch (RuntimeException failure) {
            LOG.warn("Provider model listing failed ({})", failure.getClass().getSimpleName());
            throw AiException.providerUnavailable();
        }
    }

    /**
     * Verifies an endpoint and key by listing its models, which spends no tokens, and returns how many it reports.
     * Adapters that cannot list models are not contacted. Call outside any transaction.
     */
    public int verifyProvider(ModelCatalogService.ProviderConnection connection) {
        var adapter = adapters.require(connection.adapterType());
        if (!adapter.listsModels()) return -1;
        try {
            return adapter.reportedModels(new ProviderAdapter.Connection(connection.baseUrl(), connection.credential()),
                    providerReadTimeout).size();
        } catch (AiException expected) { throw expected; }
        catch (RuntimeException failure) {
            LOG.warn("Provider connection check failed ({})", failure.getClass().getSimpleName());
            throw AiException.providerUnreachable();
        }
    }

    public static final int FALLBACK_CONTEXT_WINDOW = 32_000;

    public static ReportedModelSpec spec(ProviderAdapter.ReportedModel reported, java.util.List<ProviderAdapter.KnownModel> known) {
        var catalogModel = findKnown(reported.modelName(), known);
        Integer context = valid(reported.contextWindow(), 256, 10_000_000);
        Integer output = reported.maxOutputTokens();
        boolean fromProvider = context != null;
        if (context == null && catalogModel != null) context = catalogModel.contextWindow();
        // Onyx GEN_AI_MODEL_FALLBACK_MAX_TOKENS: a model nobody describes is budgeted as a 32,000-token window.
        if (context == null) context = FALLBACK_CONTEXT_WINDOW;
        // The context window is what one request may fill. OpenRouter reports OpenAI's total window (gpt-5-mini
        // 400,000) where OpenAI caps input at 272,000, and a 1M beta window for Claude: when both know the model,
        // the smaller window is the one every route accepts.
        else if (context != null && catalogModel != null && catalogModel.contextWindow() < context)
            context = catalogModel.contextWindow();
        if (output == null || context == null || output < 1 || output >= context)
            output = catalogModel != null && context != null && catalogModel.maxOutputTokens() < context
                    ? catalogModel.maxOutputTokens() : null;
        // Onyx sends tools to every model; an unknown model is assumed to call them, and the saved-connection check
        // probes a tool request so a model that rejects tools is caught before the first turn.
        Boolean tools = first(first(reported.toolCalling(), catalogModel == null ? null : catalogModel.capabilities().toolCalling()), true);
        Boolean vision = first(reported.vision(), catalogModel == null ? null : catalogModel.capabilities().vision());
        Boolean reasoning = first(reported.reasoning(), catalogModel == null ? null : catalogModel.capabilities().reasoning());
        // An unpublished answer limit is never guessed: it stays empty and no cap is sent. Vision and reasoning are
        // only declared when published, since declaring them changes what the request carries.
        var capabilities = new ModelSettings.Capabilities(true,
                Boolean.TRUE.equals(tools), Boolean.TRUE.equals(vision), Boolean.TRUE.equals(reasoning));
        var pricing = reported.pricing() != null ? reported.pricing() : catalogModel == null ? null : catalogModel.pricing();
        var source = fromProvider ? ReportedModelSpec.Source.PROVIDER
                : catalogModel != null ? ReportedModelSpec.Source.CATALOG : ReportedModelSpec.Source.NONE;
        return new ReportedModelSpec(reported.modelName(), context, output, capabilities, pricing, source);
    }

    /** Catalog names are bare (gpt-5-mini); endpoints may prefix them (models/gemini-2.5-pro, openai/gpt-5-mini). */
    public static ProviderAdapter.@Nullable KnownModel findKnown(String reported, java.util.List<ProviderAdapter.KnownModel> known) {
        String name = reported.startsWith("models/") ? reported.substring("models/".length()) : reported;
        for (var candidate : new String[] {name, name.substring(name.lastIndexOf('/') + 1)})
            for (var model : known) if (model.modelName().equals(candidate)) return model;
        return null;
    }

    private static @Nullable Integer valid(@Nullable Integer value, int min, int max) {
        return value == null || value < min || value > max ? null : value;
    }

    private static @Nullable Boolean first(@Nullable Boolean reported, @Nullable Boolean catalog) {
        return reported != null ? reported : catalog;
    }

    /** Acquires the native client for a selection made in a finished transaction. Call outside any transaction. */
    public Resolved acquire(ModelCatalogService.Selection selection) {
        var model = selection.model();
        var provider = selection.provider();
        if (model.settings().pricing() == null && costCapped)
            throw AiException.invalid("A cost budget requires configured model pricing.");
        var lease = clients.acquire(model.id(), provider.revision() + ":" + model.revision(), () -> {
            var adapter = adapters.require(provider.adapterType());
            var key = credentials.resolve(provider.tenantId(), provider.id(), provider.credential());
            if (adapter.credentialRequirement() == ProviderAdapter.CredentialRequirement.REQUIRED && key.isBlank())
                throw AiException.providerUnavailable();
            try {
                return adapter.create(new ProviderAdapter.Connection(provider.baseUrl(), key), model.modelName(), model.settings(), providerReadTimeout);
            } catch (AiException expected) { throw expected; }
            catch (RuntimeException failure) {
                LOG.warn("Chat model {} client initialization failed ({})", model.id(), failure.getClass().getSimpleName());
                throw AiException.providerUnavailable();
            }
        });
        if (!lease.binding().service().getName().equals(model.modelName())
                || (costCapped && lease.binding().service().getPricingModel() == null)) {
            lease.close();
            throw AiException.providerUnavailable();
        }
        return new Resolved(model.id(), selection.fallbackReason(), lease, selection.contextRevision(),
                new Provenance(provider.id(), provider.name(), provider.dataBoundary().name()));
    }
    /** The catalog provider behind a resolved model, recorded with its usage. */
    public record Provenance(@Nullable UUID providerId, String providerName, @Nullable String dataBoundary) {
        public static final Provenance UNKNOWN = new Provenance(null, "unknown", null);
    }

    public record Resolved(UUID modelConfigurationId, @Nullable String fallbackReason, ModelClients.Lease lease,
                           @Nullable String contextRevision, Provenance provenance) implements AutoCloseable {
        public Resolved(UUID modelConfigurationId, @Nullable String fallbackReason, ModelClients.Lease lease) {
            this(modelConfigurationId, fallbackReason, lease, null, Provenance.UNKNOWN);
        }
        public Resolved(UUID modelConfigurationId, @Nullable String fallbackReason, ModelClients.Lease lease,
                        @Nullable String contextRevision) {
            this(modelConfigurationId, fallbackReason, lease, contextRevision, Provenance.UNKNOWN);
        }
        public ModelBinding binding() { return lease.binding(); }
        @Override public void close() { lease.close(); }
    }
}
