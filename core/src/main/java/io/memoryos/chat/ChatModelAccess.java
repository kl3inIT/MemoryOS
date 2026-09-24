package io.memoryos.chat;

import io.memoryos.ai.ModelCatalogService;
import io.memoryos.ai.ModelCatalogService.AvailableModel;
import io.memoryos.ai.ModelCatalogService.Selection;
import io.memoryos.ai.ModelCatalogService.WebModels;
import io.memoryos.ai.ModelFlow;
import io.memoryos.chat.persistence.JdbcAgentModelRepository;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which catalog models a member may use in Chat: per conversation and per agent, the model each agent runs on, and the
 * model a turn resolves to. The catalog decides access by agent id; Chat supplies the conversation, its agent and the
 * member's personal default. No provider calls occur in these transactions.
 */
@Service
public class ChatModelAccess {
    private final ModelCatalogService catalog;
    private final JdbcAgentModelRepository agents;
    private final JdbcChatRepository chats;
    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;

    public ChatModelAccess(ModelCatalogService catalog, JdbcAgentModelRepository agents, JdbcChatRepository chats,
                           TenantAccessResolver tenants, IamAuthorization authorization) {
        this.catalog = catalog;
        this.agents = agents;
        this.chats = chats;
        this.tenants = tenants;
        this.authorization = authorization;
    }

    public record PersonaPage(List<PersonaSummary> items, @Nullable String nextCursor) {
        public PersonaPage { items = List.copyOf(items); }
    }

    /** Agents a model administrator may restrict a provider to. */
    @Transactional(readOnly = true)
    public PersonaPage personas(ActorId actor, @Nullable String cursor, int limit) {
        UUID tenant = modelManager(actor);
        if (limit < 1 || limit > 100) throw ChatException.invalid("Persona page limit must be between 1 and 100.");
        UUID after = null;
        if (cursor != null) {
            try {
                after = UUID.fromString(cursor);
                if (!after.toString().equals(cursor)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) {
                throw ChatException.invalid("Invalid Persona cursor.");
            }
            if (!agents.personaExists(tenant, actor.value(), after, agentsManage(actor))) throw ChatException.invalid("Invalid Persona cursor.");
        }
        var page = agents.personas(tenant, actor.value(), agentsManage(actor), after, limit + 1);
        boolean hasMore = page.size() > limit;
        var items = hasMore ? page.subList(0, limit) : page;
        return new PersonaPage(items, hasMore ? items.getLast().id().toString() : null);
    }

    @Transactional(readOnly = true)
    public PersonaModelDefault personaModel(ActorId actor, UUID id) {
        return agents.personaModel(modelManager(actor), actor.value(), agentsManage(actor), id);
    }

    @Transactional
    public PersonaModelDefault setPersonaModel(ActorId actor, UUID id, @Nullable UUID modelId, long revision) {
        UUID tenant = authorization.lockAndRequireExclusive(actor, IamCapability.MODELS_MANAGE).tenantId().value();
        agents.personaModel(tenant, actor.value(), agentsManage(actor), id);
        if (modelId != null) catalog.requireUsableByAgent(new TenantId(tenant), modelId, id);
        agents.setPersonaModel(tenant, actor.value(), agentsManage(actor), id, modelId, revision);
        return agents.personaModel(tenant, actor.value(), agentsManage(actor), id);
    }

    /** Models for a conversation, or for a new one with the Tenant's default agent. */
    @Transactional(readOnly = true)
    public List<AvailableModel> availableModels(ActorId actor, @Nullable UUID sessionId) {
        var tenant = tenants.findActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        UUID personaId = sessionId == null
                ? chats.defaultPersona(tenant).orElseThrow(ChatException::unavailable)
                : chats.findOwned(tenant, actor, sessionId, false).orElseThrow(ChatException::unavailable).personaId();
        return availableModels(actor, tenant, personaId);
    }

    @Transactional(readOnly = true)
    public List<AvailableModel> availableModelsForPersona(ActorId actor, UUID personaId) {
        var tenant = tenants.findActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        if (!chats.usablePersona(tenant, actor, personaId, agentsManage(actor))) throw ChatException.unavailable();
        return availableModels(actor, tenant, personaId);
    }

    @Transactional(readOnly = true)
    public WebModels availableWebModels(ActorId actor, @Nullable UUID sessionId) {
        var models = availableModels(actor, sessionId);
        var tenant = tenants.findActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        return catalog.webModels(tenant, models);
    }

    /** The model a turn runs on: the requested one, else the agent's, the member's or the Tenant's default. */
    @Transactional
    public Selection select(ActorId actor, UUID sessionId, @Nullable UUID requested) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        var session = chats.findOwned(tenant, actor, sessionId, false).orElseThrow(ChatException::unavailable);
        var context = chats.persona(sessionId, true, agentsManage(actor));
        UUID preferred = requested != null ? requested : context.modelConfigurationId();
        UUID personal = preferred == null ? agents.personalDefault(tenant.value(), actor.value()) : null;
        return catalog.select(actor, tenant, session.personaId(), preferred, personal, context.revision());
    }

    /** The flow model when it is set and still eligible; otherwise exactly the conversation model {@link #select} picks. */
    @Transactional
    public Selection selectFlow(ActorId actor, UUID sessionId, ModelFlow flow) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        chats.findOwned(tenant, actor, sessionId, false).orElseThrow(ChatException::unavailable);
        var selection = catalog.flowModel(tenant, flow);
        if (selection == null) return select(actor, sessionId, null);
        return new Selection(selection.model(), selection.provider(), null,
                chats.persona(sessionId, true, agentsManage(actor)).revision());
    }

    private List<AvailableModel> availableModels(ActorId actor, TenantId tenant, UUID personaId) {
        UUID personaDefault = agents.personaModel(tenant.value(), actor.value(), agentsManage(actor), personaId).modelConfigurationId();
        return catalog.availableModels(actor, tenant, personaId, personaDefault, agents.personalDefault(tenant.value(), actor.value()));
    }

    /** Model-manager reads run in read-only transactions, which cannot take the shared Tenant row lock. */
    private UUID modelManager(ActorId actor) {
        return authorization.require(actor, IamCapability.MODELS_MANAGE, false).tenantId().value();
    }

    private boolean agentsManage(ActorId actor) {
        return authorization.effectiveCapabilities(actor).contains(IamCapability.AGENTS_MANAGE);
    }
}
