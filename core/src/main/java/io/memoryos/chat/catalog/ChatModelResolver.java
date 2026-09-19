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
        var connection = catalog.providerConnection(actor, providerId);
        var adapter = adapters.require(connection.adapterType());
        try {
            var reported = adapter.reportedModels(
                            new ChatProviderAdapter.Connection(connection.baseUrl(), connection.credential()),
                            limits.providerReadTimeout());
            var seen = new java.util.HashSet<String>();
            return reported.stream()
                    .filter(model -> !model.modelName().isBlank() && model.modelName().length() <= 200)
                    .filter(model -> seen.add(model.modelName()))
                    .sorted(java.util.Comparator.comparing(ChatProviderAdapter.ReportedModel::modelName))
                    .limit(1000)
                    .map(model -> spec(model, adapter.knownModels()))
                    .toList();
        } catch (ChatException expected) { throw expected; }
        catch (RuntimeException failure) {
            LOG.warn("Provider {} model listing failed ({})", providerId, failure.getClass().getSimpleName());
            throw ChatException.providerUnavailable();
        }
    }

    static final int FALLBACK_CONTEXT_WINDOW = 32_000;

    public static ReportedModelSpec spec(ChatProviderAdapter.ReportedModel reported, java.util.List<ChatProviderAdapter.KnownModel> known) {
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
    public static ChatProviderAdapter.@Nullable KnownModel findKnown(String reported, java.util.List<ChatProviderAdapter.KnownModel> known) {
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
        return new Resolved(model.id(), selection.fallbackReason(), lease, selection.contextRevision());
    }
    public record Resolved(UUID modelConfigurationId, @Nullable String fallbackReason, ChatModelClients.Lease lease,
                           @Nullable String contextRevision) implements AutoCloseable {
        public Resolved(UUID modelConfigurationId, @Nullable String fallbackReason, ChatModelClients.Lease lease) {
            this(modelConfigurationId, fallbackReason, lease, null);
        }
        public ChatModelBinding binding() { return lease.binding(); }
        @Override public void close() { lease.close(); }
    }
}
