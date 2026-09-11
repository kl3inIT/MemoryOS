package io.memoryos.chat;

import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.catalog.ModelCatalogService;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JpaPersonaRepository;
import io.memoryos.chat.persistence.ChatPage;
import io.memoryos.chat.persistence.PersonaEntity;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Personal assistant settings; source/model choices only narrow the caller's existing authority. */
@Service
public class ChatPersonaService {
    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final JdbcChatRepository chats;
    private final JpaPersonaRepository settings;
    private final PersonaProperties defaults;
    private final ModelCatalogService models;
    private final SourceSearchService sources;

    public ChatPersonaService(TenantAccessResolver tenants, IamAuthorization authorization, JdbcChatRepository chats,
            JpaPersonaRepository settings, PersonaProperties defaults, ModelCatalogService models, SourceSearchService sources) {
        this.tenants = tenants; this.authorization = authorization; this.chats = chats;
        this.settings = settings; this.defaults = defaults; this.models = models; this.sources = sources;
    }

    public record PersonaInput(String name, String description, String instructions, List<String> starterPrompts,
                        List<UUID> sourceIds, boolean searchEnabled, @Nullable UUID modelConfigurationId,
                        @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit) {}
    public record PersonaView(UUID id, boolean builtin, boolean editable, long revision, String name, String description,
                       String instructions, List<String> starterPrompts, List<UUID> sourceIds, boolean searchEnabled,
                       @Nullable UUID modelConfigurationId, @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit) {}

    @Transactional
    public List<PersonaView> list(ActorId actor, int offset, int limit) {
        page(offset, limit);
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.provisionPersona(tenant, defaults.getName(), defaults.getInstructions(), defaults.getModel());
        boolean manager = manager(actor);
        return settings.readable(tenant.value(), actor.value(), new ChatPage(offset, limit)).stream().map(p -> view(p, manager)).toList();
    }

    @Transactional
    public PersonaView create(ActorId actor, PersonaInput input) {
        var tenant = write(actor);
        var entity = new PersonaEntity(UUID.randomUUID(), tenant.value(), actor.value(), defaults.getModel());
        apply(actor, entity, input);
        entity = settings.saveAndFlush(entity);
        validateModel(actor, entity);
        return view(entity, false);
    }

    @Transactional(readOnly = true)
    public PersonaView get(ActorId actor, UUID id) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        return view(owned(tenant, actor, id, false), manager(actor));
    }

    @Transactional
    public PersonaView update(ActorId actor, UUID id, long revision, PersonaInput input) {
        var tenant = write(actor);
        var entity = owned(tenant, actor, id, true);
        boolean manager = manager(actor);
        if (entity.builtin() && !manager) throw ChatException.unavailable();
        if (entity.revision() != revision) throw ChatException.conflict();
        apply(actor, entity, input);
        settings.flush();
        validateModel(actor, entity);
        return view(entity, manager);
    }

    @Transactional
    public void delete(ActorId actor, UUID id, long revision) {
        var entity = owned(write(actor), actor, id, true);
        if (entity.builtin()) throw ChatException.invalid("The default assistant cannot be deleted.");
        if (entity.revision() != revision) throw ChatException.conflict();
        entity.delete();
    }

    @Transactional
    public ChatSession select(ActorId actor, UUID sessionId, UUID personaId) {
        var tenant = write(actor);
        chats.findOwned(tenant, actor, sessionId, true).orElseThrow(ChatException::unavailable);
        if (chats.hasActiveReply(sessionId)) throw ChatException.conflict();
        owned(tenant, actor, personaId, false);
        chats.selectPersona(sessionId, personaId);
        return chats.findOwned(tenant, actor, sessionId, false).orElseThrow();
    }

    private PersonaEntity owned(TenantId tenant, ActorId actor, UUID id, boolean lock) {
        var entity = (lock ? settings.locked(tenant.value(), id) : settings.findByTenantIdAndId(tenant.value(), id))
                .orElseThrow(ChatException::unavailable);
        if (entity.deleted() || (!entity.builtin() && !Objects.equals(entity.ownerId(), actor.value()))) throw ChatException.unavailable();
        return entity;
    }
    private TenantId write(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        return tenant;
    }
    private boolean manager(ActorId actor) { return authorization.effectiveCapabilities(actor).contains(IamCapability.MODELS_MANAGE); }
    private void apply(ActorId actor, PersonaEntity entity, PersonaInput input) {
        text(input.name(), 200, true); text(input.description(), 2000, false); text(input.instructions(), 32000, false);
        if (input.starterPrompts() == null || input.starterPrompts().size() > 8 || input.sourceIds() == null || input.sourceIds().size() > 100)
            throw ChatException.invalid("Use at most 8 suggestions and 100 sources.");
        input.starterPrompts().forEach(s -> text(s, 1000, true));
        if (input.sourceIds().stream().anyMatch(Objects::isNull) || input.sourceIds().stream().distinct().count() != input.sourceIds().size())
            throw ChatException.invalid("Source selections must be unique.");
        var allowed = sources.scope(actor).sources().keySet();
        // A revoked selection may be retained to keep an allowlist narrow, but cannot be newly introduced.
        if (input.sourceIds().stream().anyMatch(id -> !allowed.contains(id) && !entity.sourceIds().contains(id)))
            throw ChatException.invalid("A selected source is unavailable.");
        if (input.contextTokenLimit() != null && (input.contextTokenLimit() < 256 || input.contextTokenLimit() > 2000000)
                || input.outputTokenLimit() != null && (input.outputTokenLimit() < 1 || input.outputTokenLimit() > 200000))
            throw ChatException.invalid("Invalid assistant token limits.");
        entity.update(input.name().strip(), input.description(), input.instructions(), input.starterPrompts(), input.sourceIds(),
                input.searchEnabled(), input.modelConfigurationId(), input.contextTokenLimit(), input.outputTokenLimit());
    }
    private void validateModel(ActorId actor, PersonaEntity entity) {
        var available = models.availableModelsForPersona(actor, entity.id());
        var selected = available.stream().filter(m -> entity.modelConfigurationId() == null ? m.isDefault() : m.id().equals(entity.modelConfigurationId()))
                .findFirst().orElseThrow(() -> ChatException.invalid("Choose an available model."));
        Integer contextLimit = entity.contextTokenLimit(), outputLimit = entity.outputTokenLimit();
        if (contextLimit != null && contextLimit >= selected.contextWindow()
                || outputLimit != null && outputLimit > selected.maxOutputTokens())
            throw ChatException.invalid("Assistant limits exceed the selected model limits.");
    }
    private static PersonaView view(PersonaEntity p, boolean manager) {
        return new PersonaView(p.id(), p.builtin(), !p.builtin() || manager, p.revision(), p.name(), p.description(), p.instructions(),
                p.starterPrompts(), p.sourceIds(), p.searchEnabled(), p.modelConfigurationId(), p.contextTokenLimit(), p.outputTokenLimit());
    }
    static void text(@Nullable String text, int max, boolean required) {
        if (text == null || text.length() > max || required && text.isBlank()) throw ChatException.invalid("Invalid text length (maximum " + max + ").");
    }
    static void page(int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) throw ChatException.invalid("Invalid page.");
    }
}
