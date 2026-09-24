package io.memoryos.chat;

import io.memoryos.chat.application.PersonaProperties;
import io.memoryos.chat.catalog.ModelCatalogService;
import io.memoryos.chat.persistence.JdbcAgentRepository.Access;
import io.memoryos.chat.persistence.JdbcAgentRepository;
import io.memoryos.chat.persistence.JdbcChatRepository;
import io.memoryos.chat.persistence.JdbcDocumentSetRepository;
import io.memoryos.chat.persistence.JdbcUserFileRepository;
import io.memoryos.chat.persistence.JpaPersonaRepository;
import io.memoryos.chat.persistence.PersonaEntity;
import io.memoryos.chat.persistence.PersonaRevisions;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.iam.group.IamAuthorization;
import io.memoryos.iam.group.IamCapability;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
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
    private static final Set<String> AVATAR_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private static final long AVATAR_MAX_BYTES = 2L * 1024 * 1024;
    private static final int MAX_SHARES = 200;

    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final JdbcChatRepository chats;
    private final JpaPersonaRepository settings;
    private final JdbcAgentRepository agents;
    private final PersonaRevisions revisions;
    private final PersonaProperties defaults;
    private final ModelCatalogService models;
    private final SourceSearchService sources;
    private final DocumentSetService documentSets;
    private final JdbcDocumentSetRepository documentSetRows;
    private final ChatFileService files;
    private final JdbcUserFileRepository userFiles;
    private final ChatFileContentService content;

    public ChatPersonaService(TenantAccessResolver tenants, IamAuthorization authorization, JdbcChatRepository chats,
            JpaPersonaRepository settings, JdbcAgentRepository agents, PersonaRevisions revisions, PersonaProperties defaults,
            ModelCatalogService models, SourceSearchService sources, DocumentSetService documentSets, JdbcDocumentSetRepository documentSetRows,
            ChatFileService files, JdbcUserFileRepository userFiles, ChatFileContentService content) {
        this.tenants = tenants; this.authorization = authorization; this.chats = chats; this.settings = settings;
        this.agents = agents; this.revisions = revisions; this.defaults = defaults; this.models = models;
        this.sources = sources; this.documentSets = documentSets; this.documentSetRows = documentSetRows;
        this.files = files; this.userFiles = userFiles; this.content = content;
    }

    /**
     * Null tools, MCP servers, files, labels, task prompt and flags keep the current value on update and use defaults
     * on create. A null knowledge cutoff clears it; an icon removes the avatar image, and omitting both keeps the image.
     */
    public record PersonaInput(String name, String description, String instructions, @Nullable String taskPrompt,
                               List<String> starterPrompts, List<UUID> sourceIds, @Nullable List<UUID> documentSetIds,
                               @Nullable Set<String> tools, @Nullable List<UUID> mcpServerIds, @Nullable UUID modelConfigurationId,
                               @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit, @Nullable List<UUID> fileIds,
                               @Nullable String iconName, @Nullable UUID avatarFileId, @Nullable List<UUID> labelIds,
                               @Nullable Boolean replaceBaseSystemPrompt, @Nullable Boolean datetimeAware, @Nullable Instant knowledgeCutoff) {
    }

    public record AgentSourceRef(UUID id, String name) {}
    public record DocumentSetRef(UUID id, String name) {}
    public record PersonaView(UUID id, boolean builtin, PersonaPermissions permissions, long revision, String name,
                              String description, String instructions, String taskPrompt, List<String> starterPrompts,
                              List<UUID> sourceIds, List<AgentSourceRef> sources, List<UUID> documentSetIds, List<DocumentSetRef> documentSets,
                              Set<String> tools, List<AgentRef> mcpServers, @Nullable UUID modelConfigurationId,
                              @Nullable Integer contextTokenLimit, @Nullable Integer outputTokenLimit, List<UUID> fileIds,
                              @Nullable String iconName, boolean hasAvatar, List<AgentRef> labels, AgentOwner owner,
                              boolean vacant, List<AgentUserShare> userShares, List<AgentGroupShare> groupShares, boolean isPublic,
                              AgentPermission publicPermission, boolean listed, boolean featured, @Nullable Integer displayPriority,
                              boolean replaceBaseSystemPrompt, boolean datetimeAware, @Nullable Instant knowledgeCutoff,
                              boolean pinned, @Nullable Instant deletedAt) {}

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
        if (!access.uses() || entity.deleted() && !manage) throw ChatException.unavailable();
        return views(tenant, actor, manage, List.of(id)).getFirst();
    }

    @Transactional
    public PersonaView create(ActorId actor, PersonaInput input) {
        var tenant = write(actor);
        if (!authorization.effectiveCapabilities(actor).contains(IamCapability.AGENTS_CREATE)) throw ChatException.unavailable();
        var entity = new PersonaEntity(UUID.randomUUID(), tenant.value(), actor.value(), defaults.getModel());
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
        var entities = new HashMap<UUID, PersonaEntity>();
        for (var id : ordered.stream().sorted().toList()) {
            var entity = locked(tenant, id);
            if (entity.builtin() || entity.deleted()) throw ChatException.unavailable();
            entities.put(id, entity);
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

    /** Onyx replaces the ordered list; duplicates, the builtin agent and inaccessible agents are dropped. */
    @Transactional
    public List<PersonaView> replacePins(ActorId actor, List<UUID> ordered) {
        var tenant = write(actor);
        if (ordered == null || ordered.size() > 100 || ordered.stream().anyMatch(Objects::isNull))
            throw ChatException.invalid("Pin at most 100 agents.");
        boolean manage = manages(actor);
        var distinct = new ArrayList<>(new LinkedHashSet<>(ordered));
        var access = agents.access(tenant.value(), actor.value(), manage, distinct);
        var entities = settings.findByTenantIdAndIdIn(tenant.value(), distinct).stream()
                .collect(Collectors.toMap(PersonaEntity::id, Function.identity()));
        var kept = distinct.stream().filter(id -> entities.containsKey(id) && !entities.get(id).builtin()
                && !entities.get(id).deleted() && access.containsKey(id) && access.get(id).uses()).toList();
        agents.replacePins(tenant.value(), actor.value(), kept);
        return views(tenant, actor, manage, kept);
    }

    @Transactional(readOnly = true)
    public AgentShareOptions shareOptions(ActorId actor, @Nullable String query, int limit) {
        var tenant = tenant(actor);
        authorization.require(actor, IamCapability.CHAT_WRITE, false);
        if (limit < 1 || limit > 50) throw ChatException.invalid("Invalid page.");
        String normalized = query == null || query.isBlank() ? null : query.strip();
        if (normalized != null && normalized.length() > 200) throw ChatException.invalid("Search text is too long.");
        return agents.shareOptions(tenant.value(), normalized, limit);
    }

    public record Avatar(ObjectContent content, String mediaType) {}

    @Transactional(readOnly = true)
    public Avatar avatar(ActorId actor, UUID id) {
        var tenant = tenant(actor);
        var entity = settings.findByTenantIdAndId(tenant.value(), id).orElseThrow(ChatException::unavailable);
        if (entity.avatarFileId() == null || entity.deleted() || !access(tenant, actor, manages(actor), id).uses())
            throw ChatException.unavailable();
        var file = userFiles.readable(tenant, actor, entity.avatarFileId(), false).orElseThrow(ChatException::unavailable).file();
        return new Avatar(content.open(actor, tenant, entity.avatarFileId()), file.mediaType());
    }

    private void apply(ActorId actor, TenantId tenant, PersonaEntity entity, PersonaInput input, boolean creating) {
        text(input.name(), 200, true); text(input.description(), 2000, false); text(input.instructions(), 32000, false);
        String taskPrompt = input.taskPrompt() == null ? (creating ? "" : entity.taskPrompt()) : input.taskPrompt();
        text(taskPrompt, 32000, false);
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
        if (input.fileIds() != null) {
            if (entity.builtin() && !input.fileIds().isEmpty()) throw ChatException.invalid("Personal files cannot be attached to the shared default assistant.");
            var retained = new HashSet<>(entity.fileIds());
            files.admit(tenant, actor, input.fileIds().stream().filter(id -> !retained.contains(id)).toList());
            if (input.fileIds().size() > 20 || input.fileIds().stream().distinct().count() != input.fileIds().size())
                throw ChatException.invalid("Use at most 20 unique files.");
            entity.files(input.fileIds());
        }
        String iconName = input.iconName() == null || input.iconName().isBlank() ? null : input.iconName();
        if (iconName != null && !iconName.matches("[a-z0-9-]{1,40}")) throw ChatException.invalid("Invalid icon.");
        // A new image replaces the icon; choosing an icon removes the image; sending neither keeps the image.
        UUID avatar = input.avatarFileId() != null ? input.avatarFileId() : iconName == null ? entity.avatarFileId() : null;
        if (avatar != null && !avatar.equals(entity.avatarFileId())) {
            var file = userFiles.readable(tenant, actor, avatar, false).orElseThrow(() -> ChatException.invalid("The avatar image is unavailable."))
                    .file();
            if (file.status() != UserFile.Status.READY || !AVATAR_TYPES.contains(file.mediaType()) || file.sizeBytes() > AVATAR_MAX_BYTES)
                throw ChatException.invalid("Use a PNG, JPEG, WebP or GIF avatar of at most 2 MiB.");
        }
        if (avatar != null) iconName = null;
        entity.update(new PersonaEntity.Settings(input.name().strip(), input.description(), input.instructions(), taskPrompt,
                input.starterPrompts(), input.sourceIds(), input.modelConfigurationId(), input.contextTokenLimit(), input.outputTokenLimit(),
                iconName, avatar,
                input.replaceBaseSystemPrompt() == null ? entity.replaceBaseSystemPrompt() : input.replaceBaseSystemPrompt(),
                input.datetimeAware() == null ? entity.datetimeAware() : input.datetimeAware(),
                input.knowledgeCutoff()));
    }

    private void relations(TenantId tenant, ActorId actor, PersonaEntity entity, PersonaInput input, boolean creating) {
        if (input.documentSetIds() != null || creating) {
            List<UUID> documentSetIds = input.documentSetIds() == null
                    ? (creating ? List.of() : documentSetRows.personaSets(tenant.value(), List.of(entity.id())).getOrDefault(entity.id(), List.of()))
                    : input.documentSetIds();
            documentSets.admitAttachments(actor, tenant, documentSetIds);
            documentSetRows.replacePersonaSets(tenant.value(), entity.id(), documentSetIds);
        }
        if (input.tools() != null || input.mcpServerIds() != null || creating) {
            Set<String> tools = input.tools() == null
                    ? (creating ? TOOLS : agents.tools(tenant.value(), entity.id())) : input.tools();
            if (tools.stream().anyMatch(tool -> !TOOLS.contains(tool))) throw ChatException.invalid("Unknown agent tool.");
            List<UUID> servers = input.mcpServerIds() == null
                    ? (creating ? List.of() : agents.mcpServers(tenant.value(), entity.id())) : input.mcpServerIds();
            if (servers.size() > 50 || servers.stream().anyMatch(Objects::isNull) || servers.stream().distinct().count() != servers.size())
                throw ChatException.invalid("Attach at most 50 unique MCP servers.");
            // The builtin agent reaches every MCP server the actor can use; it has no attachment rows.
            if (entity.builtin() && !servers.isEmpty()) throw ChatException.invalid("The default assistant uses every available MCP server.");
            if (agents.countMcpServers(tenant.value(), servers) != servers.size()) throw ChatException.invalid("A selected MCP server is unavailable.");
            agents.replaceTools(tenant.value(), entity.id(), new LinkedHashSet<>(tools), servers);
        }
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
            result.add(new PersonaView(id, entity.builtin(), permissions, entity.revision(), entity.name(), entity.description(),
                    entity.instructions(), entity.taskPrompt(), entity.starterPrompts(), entity.sourceIds(),
                    entity.sourceIds().stream().map(source -> new AgentSourceRef(source, names.getOrDefault(source, ""))).toList(),
                    documentSetIds, documentSetIds.stream().map(set -> new DocumentSetRef(set, setNames.getOrDefault(set, ""))).toList(),
                    details.tools().getOrDefault(id, Set.of()), details.mcpServers().getOrDefault(id, List.of()),
                    entity.modelConfigurationId(), entity.contextTokenLimit(), entity.outputTokenLimit(), entity.fileIds(),
                    entity.iconName(), entity.avatarFileId() != null, details.labels().getOrDefault(id, List.of()),
                    details.owners().getOrDefault(id, new AgentOwner(null, null)), granted.vacant(), userShares,
                    details.groupShares().getOrDefault(id, List.of()), entity.isPublic(), AgentPermission.valueOf(entity.publicPermission()),
                    entity.listed(), entity.featured(), entity.displayPriority(), entity.replaceBaseSystemPrompt(),
                    entity.datetimeAware(), entity.knowledgeCutoff(), details.pinned().contains(id), entity.deletedAt()));
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
        var available = models.availableModelsForPersona(actor, entity.id());
        var selected = available.stream().filter(m -> entity.modelConfigurationId() == null ? m.isDefault() : m.id().equals(entity.modelConfigurationId()))
                .findFirst().orElseThrow(() -> ChatException.invalid("Choose an available model."));
        Integer contextLimit = entity.contextTokenLimit(), outputLimit = entity.outputTokenLimit();
        if (contextLimit != null && contextLimit >= selected.contextWindow()
                || outputLimit != null && selected.maxOutputTokens() != null && outputLimit > selected.maxOutputTokens())
            throw ChatException.invalid("Assistant limits exceed the selected model limits.");
    }

    private static String labelName(@Nullable String name) {
        if (name == null || name.isBlank() || name.strip().length() > 100) throw ChatException.invalid("Label names have 1 to 100 characters.");
        return name.strip();
    }

    static void text(@Nullable String text, int max, boolean required) {
        if (text == null || text.length() > max || required && text.isBlank()) throw ChatException.invalid("Invalid text length (maximum " + max + ").");
    }

    static void page(int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) throw ChatException.invalid("Invalid page.");
    }
}
