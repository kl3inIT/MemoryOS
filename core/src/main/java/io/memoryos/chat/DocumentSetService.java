package io.memoryos.chat;

import io.memoryos.chat.persona.persistence.JdbcAgentRepository;
import io.memoryos.chat.persona.persistence.JdbcDocumentSetRepository;
import io.memoryos.connector.SourceCollectionScopeResolver;
import io.memoryos.connector.SourceSearchScope;
import io.memoryos.connector.SourceSearchService;
import io.memoryos.connector.SourceType;
import io.memoryos.iam.IamAuthorization;
import io.memoryos.iam.IamCapability;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Onyx-compatible named Source collections. They only narrow existing Source and Document authority. */
@Service
public class DocumentSetService implements SourceCollectionScopeResolver {
    private static final int MAX_SOURCES = 100;
    private static final int MAX_SETS = 10;
    private static final int MAX_SHARES = 200;

    public record Input(String name, String description, List<UUID> sourceIds, boolean isPublic) {}
    public record DocumentSetSharingInput(List<UUID> actorIds, List<UUID> groupIds) {}
    public record SourceRef(UUID id, String name) {}
    public record Permissions(boolean edit, boolean share, boolean delete, boolean manage) {}
    public record View(UUID id, Permissions permissions, long revision, String name, String description, boolean isPublic,
                       List<UUID> sourceIds, List<SourceRef> sources, int hiddenSources, List<AgentPerson> userShares,
                       List<AgentRef> groupShares, Instant createdAt, Instant updatedAt) {}

    private final TenantAccessResolver tenants;
    private final IamAuthorization authorization;
    private final SourceSearchService sources;
    private final JdbcAgentRepository agents;
    private final JdbcDocumentSetRepository sets;

    public DocumentSetService(TenantAccessResolver tenants, IamAuthorization authorization, SourceSearchService sources,
            JdbcAgentRepository agents, JdbcDocumentSetRepository sets) {
        this.tenants = tenants; this.authorization = authorization; this.sources = sources; this.agents = agents; this.sets = sets;
    }

    @Transactional(readOnly = true)
    public List<View> list(ActorId actor, int offset, int limit) {
        page(offset, limit);
        var tenant = tenant(actor);
        boolean manage = manages(actor);
        return views(tenant, actor, manage, sets.list(tenant.value(), actor.value(), manage, offset, limit));
    }

    @Transactional(readOnly = true)
    public View get(ActorId actor, UUID id) {
        var tenant = tenant(actor);
        boolean manage = manages(actor);
        return views(tenant, actor, manage, List.of(id)).stream().findFirst().orElseThrow(ChatException::unavailable);
    }

    @Transactional
    public View create(ActorId actor, Input input) {
        var tenant = write(actor);
        if (!authorization.effectiveCapabilities(actor).contains(IamCapability.AGENTS_CREATE)) throw ChatException.unavailable();
        var normalized = validateInput(actor, tenant, null, input);
        UUID id = UUID.randomUUID();
        sets.insert(tenant.value(), id, actor.value(), normalized.name(), normalized.description(), normalized.isPublic());
        sets.replaceSources(tenant.value(), id, normalized.sourceIds());
        return get(actor, id);
    }

    @Transactional
    public View update(ActorId actor, UUID id, long revision, Input input) {
        var tenant = write(actor);
        boolean manage = manages(actor);
        requireEdit(tenant, actor, manage, id, revision);
        var current = sets.read(tenant.value(), id);
        var normalized = validateInput(actor, tenant, current, input);
        if (!sets.update(tenant.value(), id, revision, normalized.name(), normalized.description(), normalized.isPublic()))
            throw ChatException.conflict();
        sets.replaceSources(tenant.value(), id, normalized.sourceIds());
        return get(actor, id);
    }

    @Transactional
    public void delete(ActorId actor, UUID id, long revision) {
        var tenant = write(actor);
        boolean manage = manages(actor);
        requireEdit(tenant, actor, manage, id, revision);
        if (!sets.delete(tenant.value(), id, revision)) throw ChatException.conflict();
    }

    @Transactional
    public View share(ActorId actor, UUID id, long revision, DocumentSetSharingInput input) {
        var tenant = write(actor);
        boolean manage = manages(actor);
        requireEdit(tenant, actor, manage, id, revision);
        if (input == null || input.actorIds() == null || input.groupIds() == null || input.actorIds().size() > MAX_SHARES
                || input.groupIds().size() > MAX_SHARES) throw ChatException.invalid("Share with at most 200 people and 200 Groups.");
        var users = unique(input.actorIds(), "People can be shared once each.");
        var groups = unique(input.groupIds(), "Groups can be shared once each.");
        if (agents.countActiveMembers(tenant.value(), users) != users.size()) throw ChatException.invalid("A selected person is not an active member.");
        if (agents.countOrdinaryGroups(tenant.value(), groups) != groups.size()) throw ChatException.invalid("A selected Group is unavailable.");
        var row = sets.read(tenant.value(), id);
        if (row != null) users.remove(row.ownerActorId());
        if (!sets.replaceShares(tenant.value(), id, revision, users, groups)) throw ChatException.conflict();
        return get(actor, id);
    }

    /** Validate future agent attachment. Every selected set must currently be usable by the editor. */
    @Transactional(readOnly = true)
    public void admitAttachments(ActorId actor, TenantId tenant, List<UUID> ids) {
        validateSetIds(ids);
        if (ids.isEmpty()) return;
        var access = sets.access(tenant.value(), actor.value(), manages(actor), ids);
        if (access.size() != ids.size() || access.values().stream().anyMatch(value -> !value.uses())) throw ChatException.invalid("A selected Document Set is unavailable.");
    }

    /** Freshly narrow a source scope. A valid empty set deliberately yields an empty scope. */
    @Override
    @Transactional(readOnly = true)
    public SourceSearchScope narrow(ActorId actor, List<UUID> ids) {
        validateSetIds(ids);
        var scope = sources.scope(actor);
        if (ids.isEmpty()) return scope;
        boolean manage = manages(actor);
        var access = sets.access(scope.tenant().value(), actor.value(), manage, ids);
        if (access.size() != ids.size() || access.values().stream().anyMatch(value -> !value.uses())) throw ChatException.unavailable();
        var selected = sets.usableSourceIds(scope.tenant().value(), actor.value(), manage, ids);
        Map<UUID, SourceType> narrowed = new LinkedHashMap<>();
        scope.sources().forEach((id, type) -> { if (selected.contains(id)) narrowed.put(id, type); });
        return new SourceSearchScope(scope.tenant(), actor, narrowed, scope.accessTokens());
    }

    private List<View> views(TenantId tenant, ActorId actor, boolean manage, Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        var access = sets.access(tenant.value(), actor.value(), manage, ids);
        var result = new ArrayList<View>();
        Set<UUID> selectable = null;
        for (UUID id : ids) {
            var row = sets.read(tenant.value(), id);
            var granted = access.get(id);
            if (row == null || row.deletedAt() != null || granted == null || !granted.uses()) continue;
            var details = sets.details(tenant.value(), id);
            boolean edit = granted.edits();
            // A viewer of a shared or public set sees only the Sources it may select; the rest stay a bare count.
            if (!edit && selectable == null) selectable = sources.scope(actor).sources().keySet();
            var visible = edit ? details.sourceIds() : details.sourceIds().stream().filter(selectable::contains).toList();
            var names = new LinkedHashMap<UUID, String>();
            sources.names(tenant, visible).forEach(source -> names.put(source.id(), source.name()));
            result.add(new View(id, new Permissions(edit, edit, edit, manage), row.revision(), row.name(), row.description(), row.isPublic(),
                    visible, visible.stream().map(source -> new SourceRef(source, names.getOrDefault(source, ""))).toList(),
                    details.sourceIds().size() - visible.size(),
                    edit ? details.userShares() : List.of(), edit ? details.groupShares() : List.of(), row.createdAt(), row.updatedAt()));
        }
        return result;
    }

    private Input validateInput(ActorId actor, TenantId tenant, JdbcDocumentSetRepository.Row current, Input input) {
        if (input == null) throw ChatException.invalid("A Document Set is required.");
        String name = text(input.name(), 200, true), description = text(input.description(), 2000, false);
        var selected = unique(input.sourceIds(), "Source selections must be unique.");
        if (selected.size() > MAX_SOURCES) throw ChatException.invalid("Use at most 100 sources.");
        var currentSources = current == null ? Set.<UUID>of() : Set.copyOf(sets.details(tenant.value(), current.id()).sourceIds());
        // Newly added IDs need current Source authority; retained IDs keep the stored allowlist narrow after revocation.
        var allowed = sources.scope(actor).sources().keySet();
        if (selected.stream().anyMatch(source -> !allowed.contains(source) && !currentSources.contains(source)))
            throw ChatException.invalid("A selected source is unavailable.");
        return new Input(name, description, List.copyOf(selected), input.isPublic());
    }

    private void requireEdit(TenantId tenant, ActorId actor, boolean manage, UUID id, long revision) {
        var row = sets.locked(tenant.value(), id);
        var access = sets.access(tenant.value(), actor.value(), manage, List.of(id)).get(id);
        if (row == null || row.deletedAt() != null || access == null || !access.edits()) throw ChatException.unavailable();
        if (row.revision() != revision) throw ChatException.conflict();
    }

    private TenantId tenant(ActorId actor) { return tenants.findActiveTenant(actor).orElseThrow(ChatException::unavailable); }
    private TenantId write(ActorId actor) { return tenants.lockActiveMembership(actor).orElseThrow(ChatException::unavailable).tenantId(); }
    private boolean manages(ActorId actor) { return authorization.effectiveCapabilities(actor).contains(IamCapability.AGENTS_MANAGE); }

    private static void page(int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) throw ChatException.invalid("Invalid page.");
    }
    private static void validateSetIds(List<UUID> ids) {
        if (ids == null || ids.size() > MAX_SETS || ids.stream().anyMatch(Objects::isNull) || new LinkedHashSet<>(ids).size() != ids.size())
            throw ChatException.invalid("Use at most 10 unique Document Sets.");
    }
    private static LinkedHashSet<UUID> unique(List<UUID> ids, String message) {
        if (ids == null || ids.stream().anyMatch(Objects::isNull)) throw ChatException.invalid(message);
        var distinct = new LinkedHashSet<>(ids);
        if (distinct.size() != ids.size()) throw ChatException.invalid(message);
        return distinct;
    }
    private static String text(String value, int limit, boolean required) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() > limit || required && normalized.isEmpty()) throw ChatException.invalid("Invalid Document Set text.");
        return normalized;
    }
}
