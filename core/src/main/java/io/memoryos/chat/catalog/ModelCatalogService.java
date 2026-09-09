package io.memoryos.chat.catalog;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JdbcModelCatalogRepository;
import io.memoryos.chat.persistence.JdbcModelCatalogRepository.Model;
import io.memoryos.chat.persistence.JdbcModelCatalogRepository.Provider;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantAccessResolver;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.NonNull;
import org.springframework.transaction.annotation.Transactional;

/** Tenant authorization and configuration resolution. No provider calls occur in these transactions. */
public class ModelCatalogService {
    private final JdbcModelCatalogRepository catalog;
    private final JdbcChatRepository chats;
    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final ChatProviderAdapters adapters;
    private final ProviderCredentials credentials;
    private final PersonaProperties persona;
    private final Deployment deployment;

    public ModelCatalogService(JdbcModelCatalogRepository catalog, JdbcChatRepository chats, TenantAccessResolver tenants,
            IamAuthorization authorization, ChatProviderAdapters adapters, ProviderCredentials credentials,
            PersonaProperties persona, Deployment deployment) {
        this.catalog = catalog;
        this.chats = chats;
        this.tenants = tenants;
        this.authorization = authorization;
        this.adapters = adapters;
        this.credentials = credentials;
        this.persona = persona;
        this.deployment = deployment;
    }

    public record Deployment(String baseUrl, String modelName, ModelSettings settings) {}
    public record ProviderInput(String name, String adapterType, String baseUrl, boolean enabled, boolean isPublic,
                                Set<UUID> groupIds, Set<UUID> personaIds, ProviderCredentials.Change credential) {
        @Override public @NonNull String toString() { return "ProviderInput[redacted]"; }
    }
    public record ModelInput(String modelName, String displayName, boolean visible, ModelSettings settings) {}
    public record ProviderView(UUID id, String name, String adapterType, String baseUrl, boolean enabled, boolean isPublic,
                               Set<UUID> groupIds, Set<UUID> personaIds, boolean credentialConfigured, long revision) {}
    public record AvailableModel(UUID id, UUID providerId, String providerName, String modelName, String displayName,
                                 ModelSettings.Capabilities capabilities, int contextWindow, int maxOutputTokens,
                                 ModelSettings.@Nullable Pricing pricing, boolean isDefault) {}
    public record Selection(Model model, Provider provider, @Nullable String fallbackReason) {}

    @Transactional
    public void requireModelsManage(ActorId actor) { admin(actor, false); }

    @Transactional
    public List<ProviderView> providers(ActorId actor) {
        UUID tenant = admin(actor, false);
        initialize(tenant);
        return catalog.providers(tenant).stream().map(this::view).toList();
    }

    @Transactional
    public ProviderView createProvider(ActorId actor, ProviderInput input) {
        UUID tenant = admin(actor, true);
        initialize(tenant);
        if (catalog.providers(tenant).size() >= 64) throw ChatException.invalid("Provider limit reached.");
        UUID id = UUID.randomUUID();
        var provider = validated(tenant, id, input, null, 1);
        catalog.insertProvider(provider, null);
        return view(provider);
    }

    @Transactional
    public ProviderView updateProvider(ActorId actor, UUID id, long revision, ProviderInput input) {
        UUID tenant = admin(actor, true);
        initialize(tenant);
        var old = catalog.provider(tenant, id).orElseThrow(ChatException::unavailable);
        if (old.revision() != revision) throw ChatException.conflict();
        if (!old.adapterType().equals(input.adapterType())) throw ChatException.invalid("Create a new provider to change its adapter type.");
        var provider = validated(tenant, id, input, old.credential(), revision);
        var models = catalog.models(tenant).stream().filter(m -> m.providerId().equals(id)).toList();
        for (var model : models) validateModel(provider, model.modelName(), model.settings());
        var defaultId = catalog.defaultModel(tenant).modelConfigurationId();
        if (models.stream().anyMatch(m -> m.id().equals(defaultId)) && !usableDefaultProvider(provider))
            throw ChatException.invalid("Choose another available public Chat default before restricting this provider.");
        catalog.updateProvider(provider);
        return view(catalog.provider(tenant, id).orElseThrow());
    }

    @Transactional
    public void deleteProvider(ActorId actor, UUID id, long revision) {
        UUID tenant = admin(actor, true);
        initialize(tenant);
        var provider = catalog.provider(tenant, id).orElseThrow(ChatException::unavailable);
        if (provider.revision() != revision) throw ChatException.conflict();
        UUID defaultId = catalog.defaultModel(tenant).modelConfigurationId();
        if (catalog.models(tenant).stream().anyMatch(m -> m.providerId().equals(id) && m.id().equals(defaultId)))
            throw ChatException.invalid("Choose another Chat default before deleting this provider.");
        catalog.deleteProvider(tenant, id, revision);
    }

    @Transactional
    public List<Model> models(ActorId actor, UUID providerId) {
        UUID tenant = admin(actor, false);
        initialize(tenant);
        catalog.provider(tenant, providerId).orElseThrow(ChatException::unavailable);
        return catalog.models(tenant).stream().filter(m -> m.providerId().equals(providerId)).toList();
    }

    @Transactional
    public Model createModel(ActorId actor, UUID providerId, ModelInput input) {
        UUID tenant = admin(actor, true);
        initialize(tenant);
        var provider = catalog.provider(tenant, providerId).orElseThrow(ChatException::unavailable);
        var all = catalog.models(tenant);
        if (all.size() >= 256) throw ChatException.invalid("Model limit reached.");
        requireUnique(all, providerId, null, input.modelName());
        var model = validated(tenant, UUID.randomUUID(), provider, input, 1);
        catalog.insertModel(model);
        return model;
    }

    @Transactional
    public Model updateModel(ActorId actor, UUID id, long revision, ModelInput input) {
        UUID tenant = admin(actor, true);
        initialize(tenant);
        var old = catalog.model(tenant, id).orElseThrow(ChatException::unavailable);
        if (old.revision() != revision) throw ChatException.conflict();
        requireUnique(catalog.models(tenant), old.providerId(), id, input.modelName());
        var provider = catalog.provider(tenant, old.providerId()).orElseThrow();
        var model = validated(tenant, id, provider, input, revision);
        if (!model.visible() && id.equals(catalog.defaultModel(tenant).modelConfigurationId()))
            throw ChatException.invalid("Choose another Chat default before hiding this model.");
        catalog.updateModel(model);
        return catalog.model(tenant, id).orElseThrow();
    }

    @Transactional
    public void deleteModel(ActorId actor, UUID id, long revision) {
        UUID tenant = admin(actor, true);
        initialize(tenant);
        var model = catalog.model(tenant, id).orElseThrow(ChatException::unavailable);
        if (model.revision() != revision) throw ChatException.conflict();
        if (id.equals(catalog.defaultModel(tenant).modelConfigurationId()))
            throw ChatException.invalid("Choose another Chat default before deleting this model.");
        catalog.deleteModel(tenant, id, revision);
    }

    @Transactional
    public JdbcModelCatalogRepository.Default defaultModel(ActorId actor) {
        UUID tenant = admin(actor, false);
        initialize(tenant);
        return catalog.defaultModel(tenant);
    }

    @Transactional
    public JdbcModelCatalogRepository.Default setDefault(ActorId actor, UUID id, long revision) {
        UUID tenant = admin(actor, true);
        initialize(tenant);
        var model = catalog.model(tenant, id).orElseThrow(ChatException::unavailable);
        var provider = catalog.provider(tenant, model.providerId()).orElseThrow();
        if (!model.visible() || !usableDefaultProvider(provider))
            throw ChatException.invalid("Chat default must be visible and available to the Tenant without Group or Persona restrictions.");
        catalog.setDefault(tenant, id, revision);
        return catalog.defaultModel(tenant);
    }

    @Transactional
    public JdbcModelCatalogRepository.PersonaModel personaModel(ActorId actor, UUID id) {
        return catalog.personaModel(admin(actor, false), id);
    }

    @Transactional
    public JdbcModelCatalogRepository.PersonaModel setPersonaModel(ActorId actor, UUID id, @Nullable UUID modelId, long revision) {
        UUID tenant = admin(actor, true);
        initialize(tenant);
        catalog.personaModel(tenant, id);
        if (modelId != null) {
            var model = catalog.model(tenant, modelId).orElseThrow(ChatException::unavailable);
            var provider = catalog.provider(tenant, model.providerId()).orElseThrow();
            if (!available(provider, id, true, Set.of())) throw ChatException.invalid("Model is unavailable to this Persona.");
        }
        catalog.setPersonaModel(tenant, id, modelId, revision);
        return catalog.personaModel(tenant, id);
    }

    @Transactional
    public List<AvailableModel> availableModels(ActorId actor, @Nullable UUID sessionId) {
        var membership = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable);
        UUID tenant = membership.tenantId().value();
        initialize(tenant);
        UUID personaId = sessionId == null
                ? chats.provisionPersona(membership.tenantId(), persona.getName(), persona.getInstructions(), persona.getModel())
                : chats.findOwned(membership.tenantId(), actor, sessionId, false).orElseThrow(ChatException::unavailable).personaId();
        var groups = catalog.actorGroups(tenant, actor.value());
        boolean manager = authorization.effectiveCapabilities(actor).contains(IamCapability.MODELS_MANAGE);
        var providers = catalog.providers(tenant).stream().collect(Collectors.toMap(Provider::id, Function.identity()));
        UUID defaultId = catalog.defaultModel(tenant).modelConfigurationId();
        return catalog.models(tenant).stream().filter(Model::visible)
                .filter(m -> providers.containsKey(m.providerId()))
                .filter(m -> available(providers.get(m.providerId()), personaId, manager, groups))
                .map(m -> new AvailableModel(m.id(), m.providerId(), providers.get(m.providerId()).name(), m.modelName(), m.displayName(),
                        m.settings().capabilities(), m.settings().contextWindow(), m.settings().maxOutputTokens(), m.settings().pricing(), m.id().equals(defaultId)))
                .toList();
    }

    @Transactional
    public Selection resolve(ActorId actor, UUID sessionId, @Nullable UUID requested) {
        var membership = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable);
        UUID tenant = membership.tenantId().value();
        var session = chats.findOwned(membership.tenantId(), actor, sessionId, false).orElseThrow(ChatException::unavailable);
        initialize(tenant);
        UUID defaultId = catalog.defaultModel(tenant).modelConfigurationId();
        UUID preferred = requested != null ? requested : catalog.personaModel(tenant, session.personaId()).modelConfigurationId();
        if (preferred == null) preferred = defaultId;
        var groups = catalog.actorGroups(tenant, actor.value());
        boolean manager = authorization.effectiveCapabilities(actor).contains(IamCapability.MODELS_MANAGE);
        var selection = accessible(tenant, preferred, session.personaId(), manager, groups);
        if (selection != null) return selection;
        var fallback = accessible(tenant, defaultId, session.personaId(), manager, groups);
        if (fallback == null) throw ChatException.providerUnavailable();
        return new Selection(fallback.model(), fallback.provider(), "SELECTION_UNAVAILABLE");
    }

    @Transactional
    public Selection validationSelection(ActorId actor, UUID id) {
        UUID tenant = admin(actor, false);
        initialize(tenant);
        var model = catalog.model(tenant, id).orElseThrow(ChatException::unavailable);
        var provider = catalog.provider(tenant, model.providerId()).orElseThrow();
        validateModel(provider, model.modelName(), model.settings());
        return new Selection(model, provider, null);
    }

    private @Nullable Selection accessible(UUID tenant, @Nullable UUID id, UUID personaId, boolean manager, Set<UUID> groups) {
        if (id == null) return null;
        var model = catalog.model(tenant, id).orElse(null);
        if (model == null) return null;
        var provider = catalog.provider(tenant, model.providerId()).orElseThrow();
        // Hidden removes a model from selection lists; it is not an access revocation.
        return available(provider, personaId, manager, groups) ? new Selection(model, provider, null) : null;
    }

    private boolean available(Provider p, UUID personaId, boolean manager, Set<UUID> groups) {
        if (!p.enabled() || !credentialUsable(p) || (!p.personaIds().isEmpty() && !p.personaIds().contains(personaId))) return false;
        return p.isPublic() || manager || p.groupIds().stream().anyMatch(groups::contains);
    }
    private boolean usableDefaultProvider(Provider p) {
        return p.enabled() && p.isPublic() && p.personaIds().isEmpty() && credentialUsable(p);
    }
    private boolean credentialUsable(Provider p) {
        return adapters.supports(p.adapterType()) && (adapters.require(p.adapterType()).credentialRequirement() != ChatProviderAdapter.CredentialRequirement.REQUIRED
                || credentials.configured(p.credential()));
    }
    private UUID admin(ActorId actor, boolean write) {
        return (write ? authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE)
                : authorization.lockAndRequire(actor, IamCapability.MODELS_MANAGE, false)).tenantId().value();
    }
    private void initialize(UUID tenant) {
        if (!catalog.initialize(tenant)) return;
        UUID providerId = UUID.randomUUID();
        var provider = new Provider(providerId, tenant, "Deployment OpenAI", "openai", deployment.baseUrl(), true, true,
                ProviderCredentials.DEPLOYMENT, 1, Set.of(), Set.of());
        validateEndpoint(provider.baseUrl());
        validateModel(provider, deployment.modelName(), deployment.settings());
        catalog.insertProvider(provider, "deployment");
        var model = new Model(UUID.randomUUID(), tenant, providerId, deployment.modelName(), deployment.modelName(), true, deployment.settings(), 1);
        catalog.insertModel(model);
        catalog.setDefault(tenant, model.id(), 1);
    }
    private Provider validated(UUID tenant, UUID id, ProviderInput input, @Nullable String previous, long revision) {
        if (input == null) throw ChatException.invalid("Provider configuration is required.");
        requireText(input.name(), 200);
        requireText(input.adapterType(), 64);
        validateEndpoint(input.baseUrl());
        var adapter = adapters.require(input.adapterType());
        if (input.groupIds() == null || input.personaIds() == null || input.groupIds().size() > 256 || input.personaIds().size() > 256)
            throw ChatException.invalid("Invalid provider access associations.");
        var groups = Set.copyOf(input.groupIds());
        var personas = Set.copyOf(input.personaIds());
        if (!catalog.associationsExist(tenant, groups, personas)) throw ChatException.invalid("Provider associations must belong to this Tenant.");
        String stored = credentials.update(tenant, id, previous, input.credential());
        if (adapter.credentialRequirement() == ChatProviderAdapter.CredentialRequirement.NONE && stored != null)
            throw ChatException.invalid("This adapter does not accept credentials.");
        if (input.enabled() && adapter.credentialRequirement() == ChatProviderAdapter.CredentialRequirement.REQUIRED && !credentials.configured(stored))
            throw ChatException.invalid("An enabled provider requires credentials.");
        return new Provider(id, tenant, input.name(), input.adapterType(), input.baseUrl(), input.enabled(), input.isPublic(), stored, revision, groups, personas);
    }
    private Model validated(UUID tenant, UUID id, Provider provider, ModelInput input, long revision) {
        if (input == null) throw ChatException.invalid("Model configuration is required.");
        requireText(input.modelName(), 200);
        requireText(input.displayName(), 200);
        validateModel(provider, input.modelName(), input.settings());
        return new Model(id, tenant, provider.id(), input.modelName(), input.displayName(), input.visible(), input.settings(), revision);
    }
    private void validateModel(Provider provider, String name, ModelSettings settings) {
        if (settings == null || !settings.capabilities().streaming()) throw ChatException.invalid("Chat requires a streaming model.");
        adapters.require(provider.adapterType()).validate(provider.baseUrl(), name, settings);
    }
    private ProviderView view(Provider p) {
        return new ProviderView(p.id(), p.name(), p.adapterType(), p.baseUrl(), p.enabled(), p.isPublic(), p.groupIds(), p.personaIds(),
                credentials.configured(p.credential()), p.revision());
    }
    public static void validateEndpoint(String url) {
        requireText(url, 2048);
        try {
            URI endpoint = URI.create(url);
            if (!("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))
                    || endpoint.getHost() == null || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null)
                throw ChatException.invalid("Provider endpoint must use HTTP(S) without credentials, query or fragment.");
        } catch (IllegalArgumentException invalid) { throw ChatException.invalid("Invalid provider endpoint."); }
    }
    private static void requireText(String text, int max) {
        if (text == null || text.isBlank() || !text.equals(text.trim()) || text.length() > max)
            throw ChatException.invalid("Invalid provider or model configuration.");
    }
    private static void requireUnique(List<Model> models, UUID provider, @Nullable UUID id, String name) {
        if (models.stream().anyMatch(m -> m.providerId().equals(provider) && !Objects.equals(id, m.id()) && m.modelName().equals(name)))
            throw ChatException.conflict();
    }
}
