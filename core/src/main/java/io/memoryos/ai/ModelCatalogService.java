package io.memoryos.ai;

import io.memoryos.audit.AuditAction;
import io.memoryos.audit.AuditRecord;
import io.memoryos.audit.AuditTrail;
import io.memoryos.ai.persistence.ModelCatalogRepository;
import io.memoryos.iam.group.Authority;
import io.memoryos.iam.group.GroupIdentityPage;
import io.memoryos.iam.group.GroupScopeService;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Tenant's model catalog: providers, models, the conversation default and flow models, and which of them a member
 * may use with an agent. Tenant authorization and configuration resolution only; no provider calls occur in these
 * transactions. Agents are Chat's; the catalog knows them only by id, as a restriction a provider may carry.
 */
@Service
public class ModelCatalogService {
    private final ModelCatalogRepository catalog;
    private final AgentDirectory agents;
    private final ApplicationEventPublisher events;
    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final ChatProviderAdapters adapters;
    private final ProviderCredentials credentials;
    private final GroupScopeService groups;
    private final AuditTrail audit;

    public ModelCatalogService(ModelCatalogRepository catalog, AgentDirectory agents, ApplicationEventPublisher events,
            TenantAccessResolver tenants, IamAuthorization authorization, ChatProviderAdapters adapters,
            ProviderCredentials credentials, GroupScopeService groups, AuditTrail audit) {
        this.audit = audit;
        this.catalog = catalog;
        this.agents = agents;
        this.events = events;
        this.tenants = tenants;
        this.authorization = authorization;
        this.adapters = adapters;
        this.credentials = credentials;
        this.groups = groups;
    }

    public record Deployment(String baseUrl, String modelName, ModelSettings settings) {}
    public record ProviderInput(String name, String adapterType, String baseUrl, boolean enabled, boolean isPublic,
                                Set<UUID> groupIds, Set<UUID> personaIds, ProviderCredentials.Change credential,
                                DataBoundary dataBoundary) {
        @Override public @NonNull String toString() { return "ProviderInput[redacted]"; }
    }
    /** Resolved connection for one provider call; the secret never reaches toString(). */
    public record ProviderConnection(String adapterType, String baseUrl, String credential) {
        @Override public @NonNull String toString() { return "ProviderConnection[redacted]"; }
    }
    /** A connection to verify before saving; {@code changed} is false when an update keeps its endpoint and key. */
    public record ProviderProbe(ProviderConnection connection, boolean changed) {}
    public record ModelInput(String modelName, String displayName, boolean visible, ModelSettings settings) {}
    public record ProviderView(UUID id, String name, String adapterType, String baseUrl, boolean enabled, boolean isPublic,
                               Set<UUID> groupIds, Set<UUID> personaIds, boolean credentialConfigured, long revision,
                               DataBoundary dataBoundary) {}
    /** A flow model is unavailable when it is set but no longer eligible; its flow then uses the conversation model. */
    public record FlowView(ModelFlow flow, @Nullable UUID modelConfigurationId, boolean available, long revision) {}
    public record AvailableModel(UUID id, UUID providerId, String providerName, String modelName, String displayName,
                                 ModelSettings.Capabilities capabilities, int contextWindow, @Nullable Integer maxOutputTokens,
                                 ModelSettings.@Nullable Pricing pricing, boolean isDefault) {}
    public record Selection(ModelConfiguration model, LlmProvider provider, @Nullable String fallbackReason, @Nullable String contextRevision) {
        public Selection(ModelConfiguration model, LlmProvider provider, @Nullable String fallbackReason) { this(model, provider, fallbackReason, null); }
    }

    @Transactional(readOnly = true)
    public void requireModelsManage(ActorId actor) { reader(actor); }

    @Transactional(readOnly = true)
    public List<ProviderView> providers(ActorId actor) {
        UUID tenant = reader(actor);
        return catalog.providers(tenant).stream().map(this::view).toList();
    }

    @Transactional
    public ProviderView createProvider(ActorId actor, ProviderInput input) {
        UUID tenant = admin(actor, true);
        if (catalog.providers(tenant).size() >= 64) throw AiException.invalid("Provider limit reached.");
        UUID id = UUID.randomUUID();
        var provider = validated(tenant, id, input, null, 1);
        catalog.insertProvider(provider, null);
        record(tenant, actor, AuditAction.PROVIDER_CREATE, "LLM_PROVIDER", id, provider.name(), event -> event
                .detail("adapter", provider.adapterType()).detail("dataBoundary", provider.dataBoundary().name()));
        return view(provider);
    }

    @Transactional
    public ProviderView updateProvider(ActorId actor, UUID id, long revision, ProviderInput input) {
        UUID tenant = admin(actor, true);
        var old = catalog.provider(tenant, id).orElseThrow(AiException::unavailable);
        if (old.revision() != revision) throw AiException.conflict();
        if (!old.adapterType().equals(input.adapterType())) throw AiException.invalid("Create a new provider to change its adapter type.");
        var provider = validated(tenant, id, input, old.credential(), revision);
        var models = catalog.models(tenant).stream().filter(m -> m.providerId().equals(id)).toList();
        for (var model : models) validateModel(provider, model.modelName(), model.settings());
        var defaultId = catalog.defaultModel(tenant).modelConfigurationId();
        if (models.stream().anyMatch(m -> m.id().equals(defaultId)) && !usableDefaultProvider(provider))
            throw AiException.invalid("Choose another available public Chat default before restricting this provider.");
        catalog.updateProvider(provider);
        record(tenant, actor, AuditAction.PROVIDER_UPDATE, "LLM_PROVIDER", id, provider.name(), event -> event
                .detail("before", providerFacts(old)).detail("after", providerFacts(provider))
                .detail("credentialChange", input.credential() == null ? "KEEP" : input.credential().action().name()));
        return view(catalog.provider(tenant, id).orElseThrow());
    }

    /**
     * The endpoint and key a provider form would use, for a connection check that runs outside this transaction. A
     * kept key on an existing provider is its stored key, as Onyx tests with the saved key when {@code api_key_changed}
     * is false.
     */
    @Transactional
    public ProviderProbe probeProvider(ActorId actor, @Nullable UUID providerId, String adapterType, String baseUrl,
                                       ProviderCredentials.@Nullable Change credential) {
        UUID tenant = admin(actor, false);
        requireText(adapterType, 64);
        validateEndpoint(baseUrl);
        adapters.require(adapterType);
        var old = providerId == null ? null : catalog.provider(tenant, providerId).orElseThrow(AiException::unavailable);
        if (old != null && !old.adapterType().equals(adapterType))
            throw AiException.invalid("Create a new provider to change its adapter type.");
        var action = credential == null ? ProviderCredentials.Action.KEEP : credential.action();
        String key = switch (action) {
            case REPLACE -> credential.value() == null ? "" : credential.value().strip();
            case KEEP -> old == null ? "" : credentials.resolve(tenant, old.id(), old.credential());
            case REMOVE -> "";
        };
        boolean changed = old == null || !old.baseUrl().equals(baseUrl) || action == ProviderCredentials.Action.REPLACE
                || (action == ProviderCredentials.Action.REMOVE && old.credential() != null);
        return new ProviderProbe(new ProviderConnection(adapterType, baseUrl, key), changed);
    }

    @Transactional
    public void deleteProvider(ActorId actor, UUID id, long revision) {
        UUID tenant = admin(actor, true);
        var provider = catalog.provider(tenant, id).orElseThrow(AiException::unavailable);
        if (provider.revision() != revision) throw AiException.conflict();
        UUID defaultId = catalog.defaultModel(tenant).modelConfigurationId();
        if (catalog.models(tenant).stream().anyMatch(m -> m.providerId().equals(id) && m.id().equals(defaultId)))
            throw AiException.invalid("Choose another Chat default before deleting this provider.");
        events.publishEvent(new ModelsRemoved(new TenantId(tenant), catalog.models(tenant).stream()
                .filter(m -> m.providerId().equals(id)).map(ModelConfiguration::id).collect(Collectors.toUnmodifiableSet())));
        catalog.deleteProvider(tenant, id, revision);
        record(tenant, actor, AuditAction.PROVIDER_DELETE, "LLM_PROVIDER", id, provider.name(),
                event -> event.detail("adapter", provider.adapterType()));
    }

    @Transactional(readOnly = true)
    public List<ModelConfiguration> models(ActorId actor, UUID providerId) {
        UUID tenant = reader(actor);
        catalog.provider(tenant, providerId).orElseThrow(AiException::unavailable);
        return catalog.models(tenant).stream().filter(m -> m.providerId().equals(providerId)).toList();
    }

    @Transactional
    public ModelConfiguration createModel(ActorId actor, UUID providerId, ModelInput input) {
        UUID tenant = admin(actor, true);
        var provider = catalog.provider(tenant, providerId).orElseThrow(AiException::unavailable);
        var all = catalog.models(tenant);
        if (all.size() >= 256) throw AiException.invalid("Model limit reached.");
        requireUnique(all, providerId, null, input.modelName());
        var model = validated(tenant, UUID.randomUUID(), provider, input, 1);
        catalog.insertModel(model);
        record(tenant, actor, AuditAction.MODEL_CREATE, "MODEL", model.id(), model.displayName(),
                event -> event.detail("provider", provider.name()));
        return model;
    }

    @Transactional
    public ModelConfiguration updateModel(ActorId actor, UUID id, long revision, ModelInput input) {
        UUID tenant = admin(actor, true);
        var old = catalog.model(tenant, id).orElseThrow(AiException::unavailable);
        if (old.revision() != revision) throw AiException.conflict();
        requireUnique(catalog.models(tenant), old.providerId(), id, input.modelName());
        var provider = catalog.provider(tenant, old.providerId()).orElseThrow();
        var model = validated(tenant, id, provider, input, revision);
        if (!model.visible() && id.equals(catalog.defaultModel(tenant).modelConfigurationId()))
            throw AiException.invalid("Choose another Chat default before hiding this model.");
        catalog.updateModel(model);
        record(tenant, actor, AuditAction.MODEL_UPDATE, "MODEL", id, model.displayName(),
                event -> event.detail("provider", provider.name()));
        return catalog.model(tenant, id).orElseThrow();
    }

    @Transactional
    public void deleteModel(ActorId actor, UUID id, long revision) {
        UUID tenant = admin(actor, true);
        var model = catalog.model(tenant, id).orElseThrow(AiException::unavailable);
        if (model.revision() != revision) throw AiException.conflict();
        if (id.equals(catalog.defaultModel(tenant).modelConfigurationId()))
            throw AiException.invalid("Choose another Chat default before deleting this model.");
        events.publishEvent(new ModelsRemoved(new TenantId(tenant), Set.of(id)));
        catalog.deleteModel(tenant, id, revision);
        String providerName = catalog.provider(tenant, model.providerId()).map(LlmProvider::name).orElse(null);
        record(tenant, actor, AuditAction.MODEL_DELETE, "MODEL", id, model.displayName(),
                event -> event.detail("provider", providerName));
    }

    @Transactional(readOnly = true)
    public ModelDefault defaultModel(ActorId actor) {
        UUID tenant = reader(actor);
        return catalog.defaultModel(tenant);
    }

    @Transactional
    public ModelDefault setDefault(ActorId actor, UUID id, long revision) {
        UUID tenant = admin(actor, true);
        var model = catalog.model(tenant, id).orElseThrow(AiException::unavailable);
        var provider = catalog.provider(tenant, model.providerId()).orElseThrow();
        if (!model.visible() || !usableDefaultProvider(provider))
            throw AiException.invalid("Chat default must be visible and available to the Tenant without Group or Persona restrictions.");
        UUID before = catalog.defaultModel(tenant).modelConfigurationId();
        catalog.setDefault(tenant, id, revision);
        if (!id.equals(before)) {
            // The Chat default decides where every conversation goes, Internal or External.
            record(tenant, actor, AuditAction.MODEL_DEFAULT_CHANGE, "MODEL", id, model.displayName(),
                    event -> event.detail("before", modelFacts(tenant, before)).detail("after", modelFacts(tenant, id)));
        }
        return catalog.defaultModel(tenant);
    }

    @Transactional(readOnly = true)
    public List<FlowView> flowDefaults(ActorId actor) {
        UUID tenant = reader(actor);
        return catalog.flowDefaults(tenant).stream().map(value -> flowView(tenant, value)).toList();
    }

    /** Sets or, with no model, clears a flow model; eligibility is the Chat default rule. */
    @Transactional
    public FlowView setFlowDefault(ActorId actor, ModelFlow flow, @Nullable UUID id, long revision) {
        UUID tenant = admin(actor, true);
        if (id != null && flowSelection(tenant, id) == null)
            throw AiException.invalid("A task model must be visible and available to the Tenant without Group or Persona restrictions.");
        UUID before = catalog.flowDefault(tenant, flow).modelConfigurationId();
        catalog.setFlowDefault(tenant, flow, id, revision);
        if (!Objects.equals(before, id)) {
            record(tenant, actor, AuditAction.MODEL_FLOW_CHANGE, "MODEL_FLOW", flow.name(), null,
                    event -> event.detail("flow", flow.name()).detail("before", modelFacts(tenant, before))
                            .detail("after", modelFacts(tenant, id)));
        }
        return flowView(tenant, catalog.flowDefault(tenant, flow));
    }

    /** Groups a model manager may associate with a provider; scoped managers see only their own. */
    @Transactional(readOnly = true)
    public GroupIdentityPage groupOptions(ActorId actor, @Nullable String search, int page, int size) {
        var access = authorization.require(actor, IamCapability.MODELS_MANAGE, false);
        return access.authority() == Authority.GLOBAL
                ? groups.listGroupOptions(access.tenantId(), search, page, size)
                : groups.listManagedGroupOptions(access.tenantId(), actor, search, page, size);
    }

    /** Reads one provider's endpoint and decrypted credential; the caller performs the provider call. */
    @Transactional(readOnly = true)
    public ProviderConnection providerConnection(ActorId actor, UUID providerId) {
        UUID tenant = reader(actor);
        var provider = catalog.provider(tenant, providerId).orElseThrow(AiException::unavailable);
        if (!provider.enabled()) throw AiException.invalid("Enable the provider before listing its models.");
        return new ProviderConnection(provider.adapterType(), provider.baseUrl(),
                credentials.resolve(tenant, providerId, provider.credential()));
    }

    public record WebModels(List<UUID> automatic, @Nullable UUID inherited, List<UUID> nativeSearch) {}

    /**
     * The visible models a member may use with an agent. The inherited one is the agent's own model when still usable,
     * then the member's personal default, then the Tenant default. The caller has checked the agent is the member's to use.
     */
    @Transactional(readOnly = true)
    public List<AvailableModel> availableModels(ActorId actor, TenantId tenantId, UUID personaId,
                                                @Nullable UUID personaDefault, @Nullable UUID personalDefault) {
        UUID tenant = tenantId.value();
        var groups = catalog.actorGroups(tenant, actor.value());
        boolean manager = authorization.effectiveCapabilities(actor).contains(IamCapability.MODELS_MANAGE);
        var providers = catalog.providers(tenant).stream().collect(Collectors.toMap(LlmProvider::id, Function.identity()));
        UUID defaultId = catalog.defaultModel(tenant).modelConfigurationId();
        UUID personal = personalDefault(tenant, personalDefault, personaId, manager, groups);
        UUID inheritedId = personaDefault != null && accessible(tenant, personaDefault, personaId, manager, groups) != null
                ? personaDefault : personal != null ? personal : defaultId;
        return catalog.models(tenant).stream().filter(ModelConfiguration::visible)
                .filter(m -> providers.containsKey(m.providerId()))
                .filter(m -> available(providers.get(m.providerId()), personaId, manager, groups))
                .map(m -> new AvailableModel(m.id(), m.providerId(), providers.get(m.providerId()).name(), m.modelName(), m.displayName(),
                        m.settings().capabilities(), m.settings().contextWindow(), m.settings().maxOutputTokens(), m.settings().pricing(), m.id().equals(inheritedId)))
                .toList();
    }

    /** Which of {@code models} can run Web search: through tools, and with the provider's own hosted search. */
    @Transactional(readOnly = true)
    public WebModels webModels(TenantId tenantId, List<AvailableModel> models) {
        UUID tenant = tenantId.value();
        var providers = catalog.providers(tenant).stream().collect(Collectors.toMap(LlmProvider::id, Function.identity()));
        var automatic = models.stream().filter(m -> m.capabilities().toolCalling()).map(AvailableModel::id).toList();
        var settings = catalog.models(tenant).stream().collect(Collectors.toMap(ModelConfiguration::id, ModelConfiguration::settings));
        var nativeSearch = models.stream().filter(m -> m.capabilities().toolCalling())
                .filter(m -> adapters.require(providers.get(m.providerId()).adapterType()).supportsNativeWebSearch(settings.get(m.id())))
                .map(AvailableModel::id).toList();
        return new WebModels(automatic, models.stream().filter(AvailableModel::isDefault).map(AvailableModel::id).findFirst().orElse(null), nativeSearch);
    }

    /**
     * The model a member's work with an agent runs on: {@code preferred} (the requested or the agent's model), else the
     * member's personal default when still usable, else the Tenant default; an unusable choice falls back to the Tenant
     * default. {@code contextRevision} is carried into the selection unchanged.
     */
    @Transactional
    public Selection select(ActorId actor, TenantId tenantId, UUID personaId, @Nullable UUID preferred,
                            @Nullable UUID personalDefault, @Nullable String contextRevision) {
        UUID tenant = tenantId.value();
        UUID defaultId = catalog.defaultModel(tenant).modelConfigurationId();
        var groups = catalog.actorGroups(tenant, actor.value());
        boolean manager = authorization.effectiveCapabilities(actor).contains(IamCapability.MODELS_MANAGE);
        // Persona model, then the member's personal default when still usable, then the Tenant default (MEM-145).
        if (preferred == null) preferred = personalDefault(tenant, personalDefault, personaId, manager, groups);
        if (preferred == null) preferred = defaultId;
        var selection = accessible(tenant, preferred, personaId, manager, groups);
        if (selection != null) return new Selection(selection.model(), selection.provider(), null, contextRevision);
        var fallback = accessible(tenant, defaultId, personaId, manager, groups);
        if (fallback == null) throw AiException.providerUnavailable();
        return new Selection(fallback.model(), fallback.provider(), "SELECTION_UNAVAILABLE", contextRevision);
    }

    /** The Tenant's model for this flow while it is set and still eligible; otherwise null. */
    @Transactional(readOnly = true)
    public @Nullable Selection flowModel(TenantId tenantId, ModelFlow flow) {
        UUID id = catalog.flowDefault(tenantId.value(), flow).modelConfigurationId();
        return id == null ? null : flowSelection(tenantId.value(), id);
    }

    /** Refuses a model an agent could not run on, before it becomes that agent's model. */
    @Transactional(readOnly = true)
    public void requireUsableByAgent(TenantId tenantId, UUID modelId, UUID personaId) {
        var model = catalog.model(tenantId.value(), modelId).orElseThrow(AiException::unavailable);
        var provider = catalog.provider(tenantId.value(), model.providerId()).orElseThrow();
        if (!available(provider, personaId, true, Set.of())) throw AiException.invalid("Model is unavailable to this Persona.");
    }

    /**
     * The flow model for background work that belongs to no conversation, such as a meeting's minutes. It takes the
     * Tenant's flow default and falls back to the Tenant's conversation default; a provider restricted to agents or
     * Groups is not usable here, because there is no persona and no member selection to check it against.
     */
    @Transactional
    public Selection resolveFlow(ActorId actor, ModelFlow flow) {
        var membership = tenants.lockActiveMembership(actor).orElseThrow(AiException::unavailable);
        UUID tenant = membership.tenantId().value();
        UUID id = catalog.flowDefault(tenant, flow).modelConfigurationId();
        var selection = id == null ? null : flowSelection(tenant, id);
        if (selection == null) selection = flowSelection(tenant, catalog.defaultModel(tenant).modelConfigurationId());
        if (selection == null) throw AiException.providerUnavailable();
        return new Selection(selection.model(), selection.provider(), null);
    }

    @Transactional(readOnly = true)
    public Selection validationSelection(ActorId actor, UUID id) {
        UUID tenant = reader(actor);
        var model = catalog.model(tenant, id).orElseThrow(AiException::unavailable);
        var provider = catalog.provider(tenant, model.providerId()).orElseThrow();
        validateModel(provider, model.modelName(), model.settings());
        return new Selection(model, provider, null);
    }

    /** The personal default only while it is visible and usable with this Persona; otherwise the caller falls back. */
    private @Nullable UUID personalDefault(UUID tenant, @Nullable UUID id, UUID personaId, boolean manager, Set<UUID> groups) {
        if (id == null) return null;
        var selection = accessible(tenant, id, personaId, manager, groups);
        return selection != null && selection.model().visible() ? id : null;
    }

    private @Nullable Selection accessible(UUID tenant, @Nullable UUID id, UUID personaId, boolean manager, Set<UUID> groups) {
        if (id == null) return null;
        var model = catalog.model(tenant, id).orElse(null);
        if (model == null) return null;
        var provider = catalog.provider(tenant, model.providerId()).orElseThrow();
        // Hidden removes a model from selection lists; it is not an access revocation.
        return available(provider, personaId, manager, groups) ? new Selection(model, provider, null) : null;
    }

    private @Nullable Selection flowSelection(UUID tenant, UUID id) {
        var model = catalog.model(tenant, id).orElse(null);
        if (model == null || !model.visible()) return null;
        var provider = catalog.provider(tenant, model.providerId()).orElseThrow();
        return usableDefaultProvider(provider) ? new Selection(model, provider, null) : null;
    }
    private FlowView flowView(UUID tenant, FlowModelDefault value) {
        UUID id = value.modelConfigurationId();
        return new FlowView(value.flow(), id, id == null || flowSelection(tenant, id) != null, value.revision());
    }
    private boolean available(LlmProvider p, UUID personaId, boolean manager, Set<UUID> groups) {
        if (!p.enabled() || !credentialUsable(p) || (!p.personaIds().isEmpty() && !p.personaIds().contains(personaId))) return false;
        // Onyx can_user_access_llm_provider: an agent-restricted provider without Groups is usable through its agents.
        if (p.isPublic()) return true;
        if (!p.groupIds().isEmpty()) return manager || p.groupIds().stream().anyMatch(groups::contains);
        return !p.personaIds().isEmpty() || manager;
    }
    private boolean usableDefaultProvider(LlmProvider p) {
        return p.enabled() && p.isPublic() && p.personaIds().isEmpty() && credentialUsable(p);
    }
    private boolean credentialUsable(LlmProvider p) {
        return adapters.supports(p.adapterType()) && (adapters.require(p.adapterType()).credentialRequirement() != ChatProviderAdapter.CredentialRequirement.REQUIRED
                || credentials.configured(p.credential()));
    }
    /** Model-manager reads run in read-only transactions, which cannot take the shared Tenant row lock. */
    private UUID reader(ActorId actor) {
        return authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
    }
    private UUID admin(ActorId actor, boolean write) {
        return (write ? authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE)
                : authorization.lockAndRequire(actor, IamCapability.MODELS_MANAGE, false)).tenantId().value();
    }
    private LlmProvider validated(UUID tenant, UUID id, ProviderInput input, @Nullable String previous, long revision) {
        if (input == null || input.dataBoundary() == null) throw AiException.invalid("Provider configuration is required.");
        requireText(input.name(), 200);
        requireText(input.adapterType(), 64);
        validateEndpoint(input.baseUrl());
        var adapter = adapters.require(input.adapterType());
        if (input.groupIds() == null || input.personaIds() == null || input.groupIds().size() > 256 || input.personaIds().size() > 256)
            throw AiException.invalid("Invalid provider access associations.");
        var groups = Set.copyOf(input.groupIds());
        var personas = Set.copyOf(input.personaIds());
        if (!catalog.groupsExist(tenant, groups) || !agents.exist(new TenantId(tenant), personas))
            throw AiException.invalid("Provider associations must belong to this Tenant.");
        String stored = credentials.update(tenant, id, previous, input.credential());
        if (adapter.credentialRequirement() == ChatProviderAdapter.CredentialRequirement.NONE && stored != null)
            throw AiException.invalid("This adapter does not accept credentials.");
        if (input.enabled() && adapter.credentialRequirement() == ChatProviderAdapter.CredentialRequirement.REQUIRED && !credentials.configured(stored))
            throw AiException.invalid("An enabled provider requires credentials.");
        return new LlmProvider(id, tenant, input.name(), input.adapterType(), input.baseUrl(), input.enabled(), input.isPublic(), stored, revision, groups, personas,
                input.dataBoundary());
    }
    private ModelConfiguration validated(UUID tenant, UUID id, LlmProvider provider, ModelInput input, long revision) {
        if (input == null) throw AiException.invalid("Model configuration is required.");
        requireText(input.modelName(), 200);
        requireText(input.displayName(), 200);
        validateModel(provider, input.modelName(), input.settings());
        return new ModelConfiguration(id, tenant, provider.id(), input.modelName(), input.displayName(), input.visible(), input.settings(), revision);
    }
    private void validateModel(LlmProvider provider, String name, ModelSettings settings) {
        validateModel(adapters, provider, name, settings);
    }
    static void validateModel(ChatProviderAdapters adapters, LlmProvider provider, String name, ModelSettings settings) {
        if (settings == null || !settings.capabilities().streaming()) throw AiException.invalid("Chat requires a streaming model.");
        var adapter = adapters.require(provider.adapterType());
        if (adapter.tokenizerProfiles().stream().noneMatch(profile -> profile.id().equals(settings.tokenizerProfile())))
            throw AiException.invalid("Unsupported tokenizer profile for this provider adapter.");
        adapter.validate(provider.baseUrl(), name, settings);
    }
    private ProviderView view(LlmProvider p) {
        return new ProviderView(p.id(), p.name(), p.adapterType(), p.baseUrl(), p.enabled(), p.isPublic(), p.groupIds(), p.personaIds(),
                credentials.configured(p.credential()), p.revision(), p.dataBoundary());
    }
    public static void validateEndpoint(String url) {
        requireText(url, 2048);
        try {
            URI endpoint = URI.create(url);
            if (!("https".equalsIgnoreCase(endpoint.getScheme()) || "http".equalsIgnoreCase(endpoint.getScheme()))
                    || endpoint.getHost() == null || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null)
                throw AiException.invalid("Provider endpoint must use HTTP(S) without credentials, query or fragment.");
        } catch (IllegalArgumentException invalid) { throw AiException.invalid("Invalid provider endpoint."); }
    }
    private static void requireText(String text, int max) {
        if (text == null || text.isBlank() || !text.equals(text.trim()) || text.length() > max)
            throw AiException.invalid("Invalid provider or model configuration.");
    }
    private static void requireUnique(List<ModelConfiguration> models, UUID provider, @Nullable UUID id, String name) {
        if (models.stream().anyMatch(m -> m.providerId().equals(provider) && !Objects.equals(id, m.id()) && m.modelName().equals(name)))
            throw AiException.conflict();
    }

    private void record(UUID tenant, ActorId actor, AuditAction action, String type, Object id,
                        @Nullable String label,
                        java.util.function.UnaryOperator<AuditRecord.Builder> details) {
        audit.record(details.apply(AuditRecord.of(action, new TenantId(tenant)).actor(actor)
                .resource(type, id, label)).build());
    }

    /** What a provider change is judged by: where data goes and who may reach it. The key itself is never recorded. */
    private static java.util.Map<String, Object> providerFacts(LlmProvider provider) {
        var facts = new java.util.LinkedHashMap<String, Object>();
        facts.put("name", provider.name());
        facts.put("baseUrl", provider.baseUrl());
        facts.put("dataBoundary", provider.dataBoundary().name());
        facts.put("enabled", provider.enabled());
        facts.put("public", provider.isPublic());
        facts.put("groups", provider.groupIds().size());
        return facts;
    }

    private java.util.@Nullable Map<String, Object> modelFacts(UUID tenant, @Nullable UUID modelId) {
        if (modelId == null) return null;
        var model = catalog.model(tenant, modelId).orElse(null);
        if (model == null) return java.util.Map.of("id", modelId.toString());
        var provider = catalog.provider(tenant, model.providerId()).orElse(null);
        var facts = new java.util.LinkedHashMap<String, Object>();
        facts.put("model", model.displayName());
        if (provider != null) {
            facts.put("provider", provider.name());
            facts.put("dataBoundary", provider.dataBoundary().name());
        }
        return facts;
    }
}
