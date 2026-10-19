package io.memoryos.chat;

import io.memoryos.ai.ReasoningEffort;
import io.memoryos.library.UserFileContentService;
import io.memoryos.library.UserFileService;
import io.memoryos.library.UserFile;
import io.memoryos.chat.persona.persistence.JdbcAgentRepository.Access;
import io.memoryos.chat.persona.persistence.JdbcAgentRepository;
import io.memoryos.chat.session.persistence.JdbcChatRepository;
import io.memoryos.chat.persona.persistence.JdbcDocumentSetRepository;
import io.memoryos.chat.persona.persistence.JpaPersonaRepository;
import io.memoryos.chat.persona.persistence.PersonaEntity;
import io.memoryos.chat.persona.persistence.PersonaRevisions;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.iam.ActorLanguageService;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import io.memoryos.objectstorage.ObjectContent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom agents (Persona) with Onyx-equivalent creation authority, sharing, discovery, per-agent tools and ownership.
 * Source, model and tool choices only narrow the reader's own authority at turn time.
 */
@Service
public class ChatPersonaService {
    public static final Set<String> TOOLS = Set.of("search", "web_search", "image_generation", "code_interpreter");
    /** The tools a new agent starts with when its creator names none: searching documents and the web (MEM-273). */
    public static final Set<String> DEFAULT_TOOLS = Set.of("search", "web_search");
    private static final Set<String> AVATAR_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private static final long AVATAR_MAX_BYTES = 2L * 1024 * 1024;
    private static final int MAX_SHARES = 200;

    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final JdbcChatRepository chats;
    private final JpaPersonaRepository settings;
    private final JdbcAgentRepository agents;
    private final PersonaRevisions revisions;
    private final ChatModelAccess models;
    private final SourceSearchService sources;
    private final DocumentSetService documentSets;
    private final JdbcDocumentSetRepository documentSetRows;
    private final UserFileService files;
    private final UserFileContentService content;
    private final ActorLanguageService languages;

    public ChatPersonaService(TenantAccessResolver tenants, IamAuthorization authorization, JdbcChatRepository chats,
            JpaPersonaRepository settings, JdbcAgentRepository agents, PersonaRevisions revisions,
            ChatModelAccess models, SourceSearchService sources, DocumentSetService documentSets, JdbcDocumentSetRepository documentSetRows,
            UserFileService files, UserFileContentService content, ActorLanguageService languages) {
        this.tenants = tenants; this.authorization = authorization; this.chats = chats; this.settings = settings;
        this.agents = agents; this.revisions = revisions; this.models = models;
        this.sources = sources; this.documentSets = documentSets; this.documentSetRows = documentSetRows;
        this.files = files; this.content = content; this.languages = languages;
    }

    /**
     * Null tools, MCP servers, files, labels and flags keep the current value on update and use defaults on create
     * (a new agent searches documents and the web). A null or blank title or greeting clears it, and a null reasoning
     * effort leaves the level to the conversation, the member and the model. A null avatar seed keeps the face (a new
     * agent draws a random one); a null avatar image keeps the image and {@code removeAvatar} drops it.
     */
    public record PersonaInput(String name, @Nullable String title, String description, String instructions,
                               @Nullable String greeting, List<String> starterPrompts, List<UUID> sourceIds,
                               @Nullable List<UUID> documentSetIds, @Nullable Set<String> tools, @Nullable List<UUID> mcpServerIds,
                               @Nullable UUID modelConfigurationId, @Nullable ReasoningEffort reasoningEffort,
                               @Nullable List<UUID> fileIds, @Nullable String avatarSeed, @Nullable UUID avatarFileId,
                               @Nullable Boolean removeAvatar, @Nullable List<UUID> labelIds, @Nullable Boolean grounded) {
        /** The longest title and greeting, which the API declares too, and the most starters (Onyx shows four). */
        public static final int MAX_TITLE = 80, MAX_GREETING = 500, MAX_STARTERS = 4;
        /** The characters of an avatar seed, as the persona table checks them. */
        public static final String AVATAR_SEED_PATTERN = "^[A-Za-z0-9_-]{1,64}$";
    }

    public record AgentSourceRef(UUID id, String name) {}
    public record DocumentSetRef(UUID id, String name) {}
    /** {@code templateKey} is set on a MemoryOS template (MEM-281), whose text is in the reader's account language. */
    public record PersonaView(UUID id, boolean builtin, @Nullable String templateKey, PersonaPermissions permissions, long revision, String name,
                              @Nullable String title, String description, String instructions, @Nullable String greeting,
                              List<String> starterPrompts,
                              List<UUID> sourceIds, List<AgentSourceRef> sources, List<UUID> documentSetIds, List<DocumentSetRef> documentSets,
                              Set<String> tools, List<AgentRef> mcpServers, @Nullable UUID modelConfigurationId,
                              @Nullable ReasoningEffort reasoningEffort, List<UUID> fileIds,
                              String avatarSeed, boolean hasAvatar, List<AgentRef> labels, AgentOwner owner,
                              boolean vacant, List<AgentUserShare> userShares, List<AgentGroupShare> groupShares, boolean isPublic,
                              AgentPermission publicPermission, boolean listed, boolean featured, @Nullable Integer displayPriority,
                              boolean grounded, boolean pinned, @Nullable Instant deletedAt) {}

    public record UserShareInput(UUID actorId, AgentPermission permission) {}
    public record GroupShareInput(UUID groupId, AgentPermission permission) {}
    public record SharingInput(List<UserShareInput> users, List<GroupShareInput> groups, @Nullable Boolean isPublic,
                               @Nullable AgentPermission publicPermission) {}
    public record TransferInput(@Nullable UUID actorId, @Nullable UUID groupId) {}
    public record ListingInput(boolean listed, boolean featured, @Nullable Integer displayPriority) {}

    @Transactional
    public List<PersonaView> list(ActorId actor, AgentListFilter view, @Nullable UUID label, @Nullable String query,
                                  int offset, int limit) {
        page(offset, limit);
        if (query != null && (query.isBlank() || query.length() > 200)) query = null;
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        agents.seedPinsOnce(tenant.value(), actor.value());
        boolean manage = manages(actor);
        return views(tenant, actor, manage, agents.list(tenant.value(), actor.value(), manage, view, label, query == null ? null : query.strip(), offset, limit));
    }

    @Transactional(readOnly = true)
    public List<PersonaView> administration(ActorId actor, boolean includeDeleted, int offset, int limit) {
        page(offset, limit);
        var tenant = tenant(actor);
        requireManage(actor);
        return views(tenant, actor, true, agents.adminList(tenant.value(), includeDeleted, offset, limit));
    }

    @Transactional(readOnly = true)
    public PersonaView get(ActorId actor, UUID id) {
        var tenant = tenant(actor);
        boolean manage = manages(actor);
        var entity = settings.findByTenantIdAndId(tenant.value(), id).orElseThrow(ChatException::unavailable);
        var access = access(tenant, actor, manage, id);
        // An administrator still opens a template they hid, to show it again (MEM-281).
        if (!(access.uses() || manage && entity.template()) || entity.deleted() && !manage) throw ChatException.unavailable();
        return views(tenant, actor, manage, List.of(id)).getFirst();
    }

    /**
     * "Tạo bản riêng" (MEM-281): the caller's own private assistant from a MemoryOS template, in their account
     * language, with the template's instructions, tools and {@code grounded} and a face of its own. Nothing records
     * which template it came from.
     */
    @Transactional
    public PersonaView copy(ActorId actor, UUID id) {
        var tenant = write(actor);
        var entity = settings.findByTenantIdAndId(tenant.value(), id).orElseThrow(ChatException::unavailable);
        if (entity.deleted() || !access(tenant, actor, manages(actor), id).uses()) throw ChatException.unavailable();
        var template = AssistantTemplates.find(entity.templateKey()).orElseThrow(ChatException::notATemplate);
        var text = template.text(languages.read(actor));
        return create(actor, new PersonaInput(text.name(), text.title(), text.description(), template.instructions(),
                text.greeting(), text.starters(), List.of(), List.of(), template.tools(), List.of(), null, null,
                List.of(), null, null, null, null, template.grounded()));
    }

    @Transactional
    public PersonaView create(ActorId actor, PersonaInput input) {
        var tenant = write(actor);
        if (!authorization.effectiveCapabilities(actor).contains(IamCapability.AGENTS_CREATE)) throw ChatException.unavailable();
        var entity = new PersonaEntity(UUID.randomUUID(), tenant.value(), actor.value());
        apply(actor, tenant, entity, input, true);
        entity = settings.saveAndFlush(entity);
        relations(tenant, actor, entity, input, true);
        validateModel(actor, entity);
        return views(tenant, actor, manages(actor), List.of(entity.id())).getFirst();
    }

    @Transactional
    public PersonaView update(ActorId actor, UUID id, long revision, PersonaInput input) {
        var tenant = write(actor);
        boolean manage = manages(actor);
        var entity = locked(tenant, id);
        if (entity.deleted() || !access(tenant, actor, manage, id).edits()) throw ChatException.unavailable();
        if (entity.revision() != revision) throw ChatException.conflict();
        apply(actor, tenant, entity, input, false);
        relations(tenant, actor, entity, input, false);
        revisions.advance(entity);
        settings.flush();
        validateModel(actor, entity);
        return views(tenant, actor, manage, List.of(id)).getFirst();
    }

    @Transactional
    public void delete(ActorId actor, UUID id, long revision) {
        var tenant = write(actor);
        var entity = locked(tenant, id);
        if (entity.builtin()) throw ChatException.invalid("The default assistant cannot be deleted.");
        if (entity.deleted() || !access(tenant, actor, manages(actor), id).owns()) throw ChatException.unavailable();
        if (entity.revision() != revision) throw ChatException.conflict();
        entity.delete();
    }

    @Transactional
    public PersonaView restore(ActorId actor, UUID id) {
        var tenant = write(actor);
        requireManage(actor);
        var entity = locked(tenant, id);
        if (!entity.deleted()) throw ChatException.conflict();
        entity.restore();
        settings.flush();
        return views(tenant, actor, true, List.of(id)).getFirst();
    }

    @Transactional
    public ChatSession select(ActorId actor, UUID sessionId, UUID personaId) {
        var tenant = write(actor);
        chats.findOwned(tenant, actor, sessionId, true).orElseThrow(ChatException::unavailable);
        if (chats.hasActiveReply(sessionId)) throw ChatException.conflict();
        var entity = settings.findByTenantIdAndId(tenant.value(), personaId).orElseThrow(ChatException::unavailable);
        if (entity.deleted() || !access(tenant, actor, manages(actor), personaId).uses()) throw ChatException.unavailable();
        chats.selectPersona(sessionId, personaId);
        return chats.findOwned(tenant, actor, sessionId, false).orElseThrow();
    }

    @Transactional
    public PersonaView share(ActorId actor, UUID id, long revision, SharingInput input) {
        var tenant = write(actor);
        boolean manage = manages(actor);
        var entity = locked(tenant, id);
        if (entity.builtin()) throw ChatException.invalid("The default assistant is available to everyone.");
        var access = access(tenant, actor, manage, id);
        if (entity.deleted() || !access.edits()) throw ChatException.unavailable();
        if (entity.revision() != revision) throw ChatException.conflict();
        if (input.users() == null || input.groups() == null || input.users().size() > MAX_SHARES || input.groups().size() > MAX_SHARES)
            throw ChatException.invalid("Share with at most 200 people and 200 Groups.");
        var users = new LinkedHashMap<UUID, AgentPermission>();
        input.users().forEach(share -> {
            if (share == null || share.actorId() == null || share.permission() == null || users.put(share.actorId(), share.permission()) != null)
                throw ChatException.invalid("People can be shared once each.");
        });
        var groups = new LinkedHashMap<UUID, AgentPermission>();
        input.groups().forEach(share -> {
            if (share == null || share.groupId() == null || share.permission() == null || groups.put(share.groupId(), share.permission()) != null)
                throw ChatException.invalid("Groups can be shared once each.");
        });
        if (agents.countActiveMembers(tenant.value(), users.keySet()) != users.size())
            throw ChatException.invalid("A selected person is not an active member.");
        if (agents.countOrdinaryGroups(tenant.value(), groups.keySet()) != groups.size())
            throw ChatException.invalid("A selected Group is unavailable.");
        if (entity.ownerId() != null) users.remove(entity.ownerId());
        agents.replaceShares(tenant.value(), id, users, groups);
        // As Onyx, only owners and agent managers change visibility; other editors keep the current setting.
        if (access.owns() && input.isPublic() != null)
            entity.publish(input.isPublic(), (input.publicPermission() == null ? AgentPermission.VIEWER : input.publicPermission()).name());
        revisions.advance(entity);
        settings.flush();
        return views(tenant, actor, manage, List.of(id)).getFirst();
    }

    @Transactional
    public void leave(ActorId actor, UUID id) {
        var tenant = write(actor);
        var entity = locked(tenant, id);
        if (!agents.removeUserShare(tenant.value(), id, actor.value())) throw ChatException.unavailable();
        revisions.advance(entity);
    }

    @Transactional
    public PersonaView transfer(ActorId actor, UUID id, long revision, TransferInput input) {
        var tenant = write(actor);
        boolean manage = manages(actor);
        var entity = locked(tenant, id);
        if (entity.builtin() || entity.deleted()) throw ChatException.unavailable();
        var access = access(tenant, actor, manage, id);
        boolean owner = actor.value().equals(entity.ownerId())
                || entity.ownerGroupId() != null && agents.memberOf(tenant.value(), actor.value(), entity.ownerGroupId());
        if (!owner && !(manage && access.vacant())) throw ChatException.unavailable();
        if (entity.revision() != revision) throw ChatException.conflict();
        if (input == null || (input.actorId() == null) == (input.groupId() == null))
            throw ChatException.invalid("Choose one new owner: a person or a Group.");
        if (input.actorId() != null && agents.countActiveMembers(tenant.value(), List.of(input.actorId())) != 1)
            throw ChatException.invalid("The new owner must be an active member.");
        if (input.groupId() != null && agents.countOrdinaryGroups(tenant.value(), List.of(input.groupId())) != 1)
            throw ChatException.invalid("The new owner Group is unavailable.");
        UUID previous = entity.ownerId();
        entity.transfer(input.actorId(), input.groupId());
        if (input.actorId() != null) agents.removeUserShare(tenant.value(), id, input.actorId());
        // The previous personal owner keeps editing access, as Onyx.
        if (previous != null && !previous.equals(input.actorId()) && agents.countActiveMembers(tenant.value(), List.of(previous)) == 1)
            agents.upsertUserShare(tenant.value(), id, previous, AgentPermission.EDITOR);
        revisions.advance(entity);
        settings.flush();
        return views(tenant, actor, manage, List.of(id)).getFirst();
    }

    @Transactional
    public PersonaView listing(ActorId actor, UUID id, long revision, ListingInput input) {
        var tenant = write(actor);
        requireManage(actor);
        var entity = locked(tenant, id);
        if (entity.builtin() || entity.deleted()) throw ChatException.unavailable();
        if (entity.revision() != revision) throw ChatException.conflict();
        // An administrator only shows or hides a MemoryOS template (MEM-281).
        if (entity.template() && (input.featured() != entity.featured()
                || !Objects.equals(input.displayPriority(), entity.displayPriority())))
            throw ChatException.invalid("A template can only be shown or hidden.");
        if (input.displayPriority() != null && (input.displayPriority() < 0 || input.displayPriority() > 100000))
            throw ChatException.invalid("Display priority must be between 0 and 100000.");
        entity.listing(input.listed(), input.featured(), input.displayPriority());
        settings.flush();
        return views(tenant, actor, true, List.of(id)).getFirst();
    }

    /**
     * Writes display priorities from one ordered list in a single transaction, so a drag never leaves a partial order.
     * Rows lock in id order; the builtin and deleted agents are rejected.
     */
    @Transactional
    public void reorder(ActorId actor, List<UUID> ordered) {
        var tenant = write(actor);
        requireManage(actor);
        if (ordered == null || ordered.size() > 1000 || ordered.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ordered).size() != ordered.size())
            throw ChatException.invalid("Order at most 1000 distinct agents.");
        if (ordered.isEmpty()) return;
        var entities = new HashMap<UUID, PersonaEntity>();
        var rows = settings.lockedAll(tenant.value(), ordered);
        if (rows.size() != ordered.size()) throw ChatException.unavailable();
        for (var entity : rows) {
            if (entity.builtin() || entity.template() || entity.deleted()) throw ChatException.unavailable();
            entities.put(entity.id(), entity);
        }
        for (int index = 0; index < ordered.size(); index++) {
            var entity = entities.get(ordered.get(index));
            if (!Objects.equals(entity.displayPriority(), index)) entity.listing(entity.listed(), entity.featured(), index);
        }
        settings.flush();
    }

    @Transactional(readOnly = true)
    public List<AgentRef> labels(ActorId actor) {
        return agents.labels(tenant(actor).value());
    }

    @Transactional
    public AgentRef createLabel(ActorId actor, String name) {
        var tenant = write(actor);
        authorization.require(actor, IamCapability.CHAT_WRITE, false);
        String normalized = labelName(name);
        if (agents.labelNameTaken(tenant.value(), normalized, null)) throw ChatException.conflict();
        var id = UUID.randomUUID();
        agents.saveLabel(tenant.value(), id, normalized);
        return new AgentRef(id, normalized);
    }

    @Transactional
    public AgentRef renameLabel(ActorId actor, UUID id, String name) {
        var tenant = write(actor);
        requireManage(actor);
        if (!agents.labelExists(tenant.value(), id)) throw ChatException.unavailable();
        String normalized = labelName(name);
        if (agents.labelNameTaken(tenant.value(), normalized, id)) throw ChatException.conflict();
        agents.saveLabel(tenant.value(), id, normalized);
        return new AgentRef(id, normalized);
    }

    @Transactional
    public void deleteLabel(ActorId actor, UUID id) {
        var tenant = write(actor);
        requireManage(actor);
        if (!agents.deleteLabel(tenant.value(), id)) throw ChatException.unavailable();
    }

    @Transactional
    public List<PersonaView> pins(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        agents.seedPinsOnce(tenant.value(), actor.value());
        boolean manage = manages(actor);
        var pinned = agents.pins(tenant.value(), actor.value());
        var access = agents.access(tenant.value(), actor.value(), manage, pinned);
        var usable = pinned.stream().filter(id -> access.containsKey(id) && access.get(id).uses()).toList();
        var deleted = settings.findByTenantIdAndIdIn(tenant.value(), usable).stream().filter(PersonaEntity::deleted)
                .map(PersonaEntity::id).collect(Collectors.toSet());
        return views(tenant, actor, manage, usable.stream().filter(id -> !deleted.contains(id)).toList());
    }

    /**
     * Onyx replaces the ordered list; duplicates, the builtin agent and agents the actor cannot use are dropped. A pin
     * that is already there stays even when its agent became unusable, so reordering the list keeps that row
     * (MEM-273); the answer lists the usable pins only, as {@link #pins} does.
     */
    @Transactional
    public List<PersonaView> replacePins(ActorId actor, List<UUID> ordered) {
        var tenant = write(actor);
        if (ordered == null || ordered.size() > 100 || ordered.stream().anyMatch(Objects::isNull))
            throw ChatException.invalid("Pin at most 100 agents.");
        boolean manage = manages(actor);
        var distinct = new ArrayList<>(new LinkedHashSet<>(ordered));
        var current = Set.copyOf(agents.pins(tenant.value(), actor.value()));
        var access = agents.access(tenant.value(), actor.value(), manage, distinct);
        var entities = settings.findByTenantIdAndIdIn(tenant.value(), distinct).stream()
                .collect(Collectors.toMap(PersonaEntity::id, Function.identity()));
        var kept = distinct.stream().filter(id -> entities.containsKey(id) && !entities.get(id).builtin()
                && (current.contains(id) || usable(entities.get(id), access.get(id)))).toList();
        agents.replacePins(tenant.value(), actor.value(), kept);
        return views(tenant, actor, manage, kept.stream().filter(id -> usable(entities.get(id), access.get(id))).toList());
    }

    /**
     * The person's list of assistants (MEM-273): the built-in assistant first, then their pins in order, each with
     * their newest web topic. A pin they can no longer use stays, marked unusable. A fixed number of statements
     * whatever the number of pins.
     */
    @Transactional
    public List<ChatAssistant> assistants(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        agents.seedPinsOnce(tenant.value(), actor.value());
        boolean manage = manages(actor);
        var ids = new ArrayList<UUID>();
        chats.defaultPersona(tenant).ifPresent(ids::add);
        agents.pins(tenant.value(), actor.value()).stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        var entities = settings.findByTenantIdAndIdIn(tenant.value(), ids).stream()
                .collect(Collectors.toMap(PersonaEntity::id, Function.identity()));
        var access = agents.access(tenant.value(), actor.value(), manage, ids);
        var usable = ids.stream().filter(id -> entities.containsKey(id) && usable(entities.get(id), access.get(id)))
                .collect(Collectors.toSet());
        var owners = agents.details(tenant.value(), actor.value(), usable).owners();
        var topics = chats.latestTopics(tenant, actor, ids).stream()
                .collect(Collectors.toMap(JdbcChatRepository.LatestTopic::personaId, Function.identity()));
        var empty = new AgentOwner(null, null);
        String language = languages.read(actor);
        var result = new ArrayList<ChatAssistant>();
        for (UUID id : ids) {
            var entity = entities.get(id);
            if (entity == null) continue;
            boolean uses = usable.contains(id);
            var topic = topics.get(id);
            var text = AssistantTemplates.find(entity.templateKey()).map(template -> template.text(language)).orElse(null);
            result.add(new ChatAssistant(id, text == null ? entity.name() : text.name(),
                    !uses ? null : text == null ? entity.title() : text.title(),
                    !uses ? "" : text == null ? entity.description() : text.description(),
                    entity.avatarSeed(), uses && entity.avatarFileId() != null, entity.builtin(), uses ? owners.getOrDefault(id, empty) : empty,
                    uses, topic == null ? null : new ChatAssistantTopic(topic.sessionId(), topic.title(),
                            topic.messageAt() == null ? topic.updatedAt() : topic.messageAt(), preview(topic.content()),
                            topic.replyRunning())));
        }
        return result;
    }

    /**
     * The names of the Tenant's live custom assistants among {@code ids}, for a surface that shows which assistant
     * something is bound to, such as a bot (MEM-273). A deleted or built-in one is left out: it is not bound.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> names(ActorId actor, Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var tenant = tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
        return settings.findByTenantIdAndIdIn(tenant.value(), List.copyOf(Set.copyOf(ids))).stream()
                .filter(entity -> !entity.deleted() && !entity.builtin())
                .collect(Collectors.toUnmodifiableMap(PersonaEntity::id, PersonaEntity::name));
    }

    private static boolean usable(PersonaEntity entity, @Nullable Access access) {
        return !entity.deleted() && access != null && access.uses();
    }

    private static final Pattern CODE_BLOCK = Pattern.compile("```.*?(```|$)", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("!?\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern CITATION = Pattern.compile("\\s*\\[\\^?\\d+]");
    private static final Pattern MARKUP = Pattern.compile("[*_`#>|~]+");
    private static final Pattern SPACE = Pattern.compile("\\s+");

    /** A Markdown message as one line of plain text for a sidebar row, without code, links or citation markers. */
    static String preview(@Nullable String markdown) {
        if (markdown == null) return "";
        String text = CODE_BLOCK.matcher(markdown).replaceAll(" ");
        text = LINK.matcher(text).replaceAll("$1");
        text = CITATION.matcher(text).replaceAll("");
        text = MARKUP.matcher(text).replaceAll("");
        text = SPACE.matcher(text).replaceAll(" ").strip();
        if (text.codePointCount(0, text.length()) <= ChatAssistantTopic.PREVIEW_LENGTH) return text;
        return text.substring(0, text.offsetByCodePoints(0, ChatAssistantTopic.PREVIEW_LENGTH - 1)).stripTrailing() + "…";
    }

    public record Avatar(ObjectContent content, String mediaType) {}

    @Transactional(readOnly = true)
    public Avatar avatar(ActorId actor, UUID id) {
        var tenant = tenant(actor);
        var entity = settings.findByTenantIdAndId(tenant.value(), id).orElseThrow(ChatException::unavailable);
        if (entity.avatarFileId() == null || entity.deleted() || !access(tenant, actor, manages(actor), id).uses())
            throw ChatException.unavailable();
        var file = files.readable(tenant, actor, entity.avatarFileId()).orElseThrow(ChatException::unavailable);
        return new Avatar(content.open(actor, tenant, entity.avatarFileId()), file.mediaType());
    }

    /**
     * The editor's unsaved settings for one test turn (MEM-273): checked as Save checks them, under the same edit
     * authority, and never written. Kept values (null tools, servers, Document Sets, files or {@code grounded}) are
     * the saved assistant's.
     */
    @Transactional(readOnly = true)
    public JdbcChatRepository.DraftAgent draft(ActorId actor, UUID id, PersonaInput input) {
        var tenant = tenant(actor);
        var entity = settings.findByTenantIdAndId(tenant.value(), id).orElseThrow(ChatException::unavailable);
        if (entity.deleted() || !access(tenant, actor, manages(actor), id).edits()) throw ChatException.unavailable();
        var checked = check(actor, tenant, entity, input);
        var relations = checkRelations(tenant, actor, entity, input, false);
        var values = checked.settings();
        validateModel(actor, id, values.modelId(), values.reasoningEffort());
        var documentSetIds = relations.documentSetIds() != null ? relations.documentSetIds()
                : documentSetRows.personaSets(tenant.value(), List.of(id)).getOrDefault(id, List.of());
        var tools = relations.tools() != null ? relations.tools() : agents.tools(tenant.value(), id);
        var servers = relations.mcpServerIds() != null ? relations.mcpServerIds() : agents.mcpServers(tenant.value(), id);
        return new JdbcChatRepository.DraftAgent(values.instructions(), tools, entity.builtin() ? null : servers,
                values.sources(), documentSetIds, values.modelId(), values.reasoningEffort(),
                entity.builtin() ? List.of() : checked.fileIds() != null ? checked.fileIds() : entity.fileIds(),
                values.grounded());
    }

    private void apply(ActorId actor, TenantId tenant, PersonaEntity entity, PersonaInput input, boolean creating) {
        var checked = check(actor, tenant, entity, input);
        if (checked.fileIds() != null) entity.files(checked.fileIds());
        entity.update(checked.settings());
    }

    /** Settings checked as Save checks them; {@code fileIds} is null when the input keeps the attached files. */
    private record Checked(PersonaEntity.Settings settings, @Nullable List<UUID> fileIds) {}

    private Checked check(ActorId actor, TenantId tenant, PersonaEntity entity, PersonaInput input) {
        text(input.name(), 200, true); text(input.description(), 2000, false); text(input.instructions(), 32000, false);
        String title = optional(input.title(), PersonaInput.MAX_TITLE), greeting = optional(input.greeting(), PersonaInput.MAX_GREETING);
        if (input.starterPrompts() == null || input.starterPrompts().size() > PersonaInput.MAX_STARTERS || input.sourceIds() == null || input.sourceIds().size() > 100)
            throw ChatException.invalid("Use at most " + PersonaInput.MAX_STARTERS + " suggestions and 100 sources.");
        input.starterPrompts().forEach(s -> text(s, 1000, true));
        if (input.sourceIds().stream().anyMatch(Objects::isNull) || input.sourceIds().stream().distinct().count() != input.sourceIds().size())
            throw ChatException.invalid("Source selections must be unique.");
        var allowed = sources.scope(actor).sources().keySet();
        // A revoked selection may be retained to keep an allowlist narrow, but cannot be newly introduced.
        if (input.sourceIds().stream().anyMatch(id -> !allowed.contains(id) && !entity.sourceIds().contains(id)))
            throw ChatException.invalid("A selected source is unavailable.");
        if (input.fileIds() != null) {
            if (entity.builtin() && !input.fileIds().isEmpty()) throw ChatException.invalid("Personal files cannot be attached to the shared default assistant.");
            var retained = new HashSet<>(entity.fileIds());
            files.admit(tenant, actor, input.fileIds().stream().filter(id -> !retained.contains(id)).toList());
            if (input.fileIds().size() > 20 || input.fileIds().stream().distinct().count() != input.fileIds().size())
                throw ChatException.invalid("Use at most 20 unique files.");
        }
        String avatarSeed = input.avatarSeed();
        if (avatarSeed != null && !avatarSeed.matches(PersonaInput.AVATAR_SEED_PATTERN)) throw ChatException.invalid("Invalid avatar.");
        // A new image replaces the old one; removeAvatar drops it so the face shows; sending neither keeps the image.
        UUID avatar = input.avatarFileId() != null ? input.avatarFileId()
                : Boolean.TRUE.equals(input.removeAvatar()) ? null : entity.avatarFileId();
        if (avatar != null && !avatar.equals(entity.avatarFileId())) {
            var file = files.readable(tenant, actor, avatar).orElseThrow(() -> ChatException.invalid("The avatar image is unavailable."));
            if (file.status() != UserFile.Status.READY || !AVATAR_TYPES.contains(file.mediaType()) || file.sizeBytes() > AVATAR_MAX_BYTES)
                throw ChatException.invalid("Use a PNG, JPEG, WebP or GIF avatar of at most 2 MiB.");
        }
        return new Checked(new PersonaEntity.Settings(input.name().strip(), title, input.description(), input.instructions(),
                greeting, input.starterPrompts(), input.sourceIds(), input.modelConfigurationId(), input.reasoningEffort(),
                avatarSeed, avatar, input.grounded() == null ? entity.grounded() : input.grounded()), input.fileIds());
    }

    /** Attachments checked as Save checks them; null where the input keeps what is saved. Labels are not among them. */
    private record Relations(@Nullable List<UUID> documentSetIds, @Nullable Set<String> tools,
                             @Nullable List<UUID> mcpServerIds) {}

    private Relations checkRelations(TenantId tenant, ActorId actor, PersonaEntity entity, PersonaInput input, boolean creating) {
        List<UUID> documentSetIds = null;
        if (input.documentSetIds() != null || creating) {
            // Only a new agent reaches here without a selection.
            documentSetIds = input.documentSetIds() == null ? List.of() : input.documentSetIds();
            documentSets.admitAttachments(actor, tenant, documentSetIds);
        }
        Set<String> tools = null;
        List<UUID> servers = null;
        if (input.tools() != null || input.mcpServerIds() != null || creating) {
            tools = input.tools() == null
                    ? (creating ? DEFAULT_TOOLS : agents.tools(tenant.value(), entity.id())) : input.tools();
            if (tools.stream().anyMatch(tool -> !TOOLS.contains(tool))) throw ChatException.invalid("Unknown agent tool.");
            servers = input.mcpServerIds() == null
                    ? (creating ? List.of() : agents.mcpServers(tenant.value(), entity.id())) : input.mcpServerIds();
            if (servers.size() > 50 || servers.stream().anyMatch(Objects::isNull) || servers.stream().distinct().count() != servers.size())
                throw ChatException.invalid("Attach at most 50 unique MCP servers.");
            // The builtin agent reaches every MCP server the actor can use; it has no attachment rows.
            if (entity.builtin() && !servers.isEmpty()) throw ChatException.invalid("The default assistant uses every available MCP server.");
            if (agents.countMcpServers(tenant.value(), servers) != servers.size()) throw ChatException.invalid("A selected MCP server is unavailable.");
        }
        return new Relations(documentSetIds, tools, servers);
    }

    private void relations(TenantId tenant, ActorId actor, PersonaEntity entity, PersonaInput input, boolean creating) {
        var checked = checkRelations(tenant, actor, entity, input, creating);
        if (checked.documentSetIds() != null)
            documentSetRows.replacePersonaSets(tenant.value(), entity.id(), checked.documentSetIds());
        if (checked.tools() != null)
            agents.replaceTools(tenant.value(), entity.id(), new LinkedHashSet<>(checked.tools()), Objects.requireNonNull(checked.mcpServerIds()));
        if (input.labelIds() != null) {
            if (input.labelIds().size() > 20 || input.labelIds().stream().anyMatch(Objects::isNull)
                    || input.labelIds().stream().distinct().count() != input.labelIds().size())
                throw ChatException.invalid("Use at most 20 unique labels.");
            if (agents.countLabels(tenant.value(), input.labelIds()) != input.labelIds().size())
                throw ChatException.invalid("A selected label is unavailable.");
            agents.replaceLabels(tenant.value(), entity.id(), input.labelIds());
        }
    }

    private List<PersonaView> views(TenantId tenant, ActorId actor, boolean manage, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        var entities = settings.findByTenantIdAndIdIn(tenant.value(), ids).stream()
                .collect(Collectors.toMap(PersonaEntity::id, Function.identity()));
        var access = agents.access(tenant.value(), actor.value(), manage, ids);
        var details = agents.details(tenant.value(), actor.value(), ids);
        var sourceIds = entities.values().stream().flatMap(entity -> entity.sourceIds().stream()).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, String> names = new HashMap<>();
        if (!sourceIds.isEmpty()) {
            var batch = new ArrayList<UUID>();
            for (UUID sourceId : sourceIds) {
                batch.add(sourceId);
                if (batch.size() == 500) { names(tenant, batch, names); batch.clear(); }
            }
            names(tenant, batch, names);
        }
        var attachedSets = documentSetRows.personaSets(tenant.value(), ids);
        var setIds = attachedSets.values().stream().flatMap(Collection::stream).collect(Collectors.toCollection(LinkedHashSet::new));
        var setNames = documentSetRows.names(tenant.value(), setIds);
        String language = languages.read(actor);
        var result = new ArrayList<PersonaView>();
        for (UUID id : ids) {
            var entity = entities.get(id);
            var granted = access.get(id);
            if (entity == null || granted == null) continue;
            var userShares = details.userShares().getOrDefault(id, List.of());
            boolean leave = userShares.stream().anyMatch(share -> share.person().actorId().equals(actor.value()));
            boolean personallyOwned = actor.value().equals(entity.ownerId());
            var permissions = new PersonaPermissions(granted.edits(), !entity.builtin() && granted.edits(),
                    granted.owns(), !entity.builtin() && granted.owns(),
                    !entity.builtin() && (personallyOwned || granted.owns() && !manage || manage && granted.vacant()),
                    leave, manage);
            var documentSetIds = attachedSets.getOrDefault(id, List.of());
            var template = AssistantTemplates.find(entity.templateKey()).orElse(null);
            var text = template == null ? null : template.text(language);
            result.add(new PersonaView(id, entity.builtin(),
                    template == null ? null : template.key(),
                    permissions, entity.revision(), text == null ? entity.name() : text.name(),
                    text == null ? entity.title() : text.title(), text == null ? entity.description() : text.description(),
                    entity.instructions(), text == null ? entity.greeting() : text.greeting(),
                    text == null ? entity.starterPrompts() : text.starters(), entity.sourceIds(),
                    entity.sourceIds().stream().map(source -> new AgentSourceRef(source, names.getOrDefault(source, ""))).toList(),
                    documentSetIds, documentSetIds.stream().map(set -> new DocumentSetRef(set, setNames.getOrDefault(set, ""))).toList(),
                    details.tools().getOrDefault(id, Set.of()), details.mcpServers().getOrDefault(id, List.of()),
                    entity.modelConfigurationId(), entity.reasoningEffort(), entity.fileIds(),
                    entity.avatarSeed(), entity.avatarFileId() != null, details.labels().getOrDefault(id, List.of()),
                    details.owners().getOrDefault(id, new AgentOwner(null, null)), granted.vacant(), userShares,
                    details.groupShares().getOrDefault(id, List.of()), entity.isPublic(), AgentPermission.valueOf(entity.publicPermission()),
                    entity.listed(), entity.featured(), entity.displayPriority(), entity.grounded(),
                    details.pinned().contains(id), entity.deletedAt()));
        }
        return result;
    }

    private void names(TenantId tenant, Collection<UUID> ids, Map<UUID, String> names) {
        sources.names(tenant, ids).forEach(option -> names.put(option.id(), option.name()));
    }

    private Access access(TenantId tenant, ActorId actor, boolean manage, UUID id) {
        var access = agents.access(tenant.value(), actor.value(), manage, List.of(id)).get(id);
        if (access == null) throw ChatException.unavailable();
        return access;
    }

    private PersonaEntity locked(TenantId tenant, UUID id) {
        return settings.locked(tenant.value(), id).orElseThrow(ChatException::unavailable);
    }

    private TenantId tenant(ActorId actor) {
        return tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable);
    }

    private TenantId write(ActorId actor) {
        var tenant = tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId();
        chats.lockOwner(tenant, actor);
        return tenant;
    }

    private boolean manages(ActorId actor) {
        return authorization.effectiveCapabilities(actor).contains(IamCapability.AGENTS_MANAGE);
    }

    private void requireManage(ActorId actor) {
        if (!manages(actor)) throw ChatException.unavailable();
    }

    private void validateModel(ActorId actor, PersonaEntity entity) {
        validateModel(actor, entity.id(), entity.modelConfigurationId(), entity.reasoningEffort());
    }

    private void validateModel(ActorId actor, UUID id, @Nullable UUID model, @Nullable ReasoningEffort effort) {
        var available = models.availableModelsForPersona(actor, id);
        var selected = available.stream().filter(m -> model == null ? m.isDefault() : m.id().equals(model))
                .findFirst().orElseThrow(() -> ChatException.invalid("Choose an available model."));
        if (effort != null && !selected.capabilities().reasoning())
            throw ChatException.invalid("The selected model does not reason.");
    }

    private static String labelName(@Nullable String name) {
        if (name == null || name.isBlank() || name.strip().length() > 100) throw ChatException.invalid("Label names have 1 to 100 characters.");
        return name.strip();
    }

    static void text(@Nullable String text, int max, boolean required) {
        if (text == null || text.length() > max || required && text.isBlank()) throw ChatException.invalid("Invalid text length (maximum " + max + ").");
    }

    /** An optional single text: blank clears it, and the rest is kept stripped within {@code max} characters. */
    private static @Nullable String optional(@Nullable String text, int max) {
        if (text == null || text.isBlank()) return null;
        text(text.strip(), max, false);
        return text.strip();
    }

    static void page(int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) throw ChatException.invalid("Invalid page.");
    }
}
