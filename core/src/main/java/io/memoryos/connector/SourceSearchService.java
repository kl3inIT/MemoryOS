package io.memoryos.connector;

import io.memoryos.connector.source.persistence.JdbcSourceDocumentRepository;
import io.memoryos.document.DocumentId;
import io.memoryos.objectstorage.StoredObjectReference;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.shared.TenantId;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Connector owns source eligibility and metadata; callers never infer authority from indexed metadata. */
@Service
public class SourceSearchService {
    private final TenantAccessResolver tenants;
    private final JdbcSourceDocumentRepository documents;

    public SourceSearchService(TenantAccessResolver tenants, JdbcSourceDocumentRepository documents) {
        this.tenants = tenants;
        this.documents = documents;
    }

    public SourceSearchScope scope(ActorId actor) {
        var tenant = tenants.findActiveTenant(actor).orElseThrow(SourceException::notFound);
        return new SourceSearchScope(tenant, actor, documents.searchableSources(tenant, actor), documents.actorAccessTokens(tenant, actor));
    }

    /** Index access tokens for direct Search, which has no Source scope. */
    public Set<String> accessTokens(TenantId tenant, ActorId actor) {
        return documents.actorAccessTokens(tenant, actor);
    }

    public DocumentAccess indexAccess(TenantId tenant, DocumentId document) {
        return documents.documentAccess(tenant, document.value());
    }

    /** Index-time access of several documents of one Tenant in one read; every requested document has an entry. */
    public Map<DocumentId, DocumentAccess> indexAccess(TenantId tenant, Collection<DocumentId> documents) {
        var result = new HashMap<DocumentId, DocumentAccess>();
        this.documents.documentAccess(tenant, documents.stream().map(DocumentId::value).distinct().toList())
                .forEach((document, access) -> result.put(new DocumentId(document), access));
        return Map.copyOf(result);
    }

    public record SourceOption(UUID id, String name, SourceType type) {}

    public List<SourceOption> options(ActorId actor, int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) throw SourceException.invalid("Invalid source page", "source option page out of bounds");
        var tenant = tenants.findActiveTenant(actor).orElseThrow(SourceException::notFound);
        return documents.searchableSourceOptions(tenant, actor, offset, limit);
    }

    /** Tenant-scoped Source names regardless of the reader's Source authority; names never grant search access. */
    public List<SourceOption> names(TenantId tenant, Collection<UUID> ids) {
        if (ids.size() > 500) throw SourceException.invalid("Invalid source page", "source name lookup out of bounds");
        return ids.isEmpty() ? List.of() : documents.sourceNames(tenant, ids);
    }

    public Map<UUID, List<DocumentSourceMetadata>> readableMetadata(SourceSearchScope scope, List<UUID> ids) {
        if (tenants.findActiveTenant(scope.actor()).filter(scope.tenant()::equals).isEmpty()) return Map.of();
        var metadata = new LinkedHashMap<UUID, List<DocumentSourceMetadata>>();
        documents.sourceMetadata(scope.tenant(), ids, scope.actor(), null).forEach((document, origins) -> {
            var visible = origins.stream().filter(origin -> scope.sources().containsKey(origin.sourceId())).toList();
            if (!visible.isEmpty()) metadata.put(document, visible);
        });
        return Map.copyOf(metadata);
    }

    /** Original PDF object for presentation; callers still check Document eligibility and generation. */
    /** Original source objects of any media type for readable, eligible Documents; callers still check generation. */
    public Map<UUID, StoredObjectReference> originals(TenantId tenant, ActorId actor,
            Set<UUID> documents) {
        return documents.isEmpty() ? Map.of() : this.documents.originals(tenant, actor, documents);
    }

    public List<DocumentSourceMetadata> indexMetadata(TenantId tenant, DocumentId document, UUID generation) {
        return documents.sourceMetadata(tenant, List.of(document.value()), null, generation)
                .getOrDefault(document.value(), List.of());
    }

    /**
     * {@link #indexMetadata(TenantId, DocumentId, UUID)} of several documents of one Tenant in one read, each at its
     * own generation; a document without eligible metadata has an empty list.
     */
    public Map<DocumentId, List<DocumentSourceMetadata>> indexMetadata(TenantId tenant, Map<DocumentId, UUID> generations) {
        var byId = new HashMap<UUID, UUID>();
        generations.forEach((document, generation) -> byId.put(document.value(), generation));
        var found = documents.indexMetadata(tenant, byId);
        var result = new HashMap<DocumentId, List<DocumentSourceMetadata>>();
        generations.keySet().forEach(document -> result.put(document, found.getOrDefault(document.value(), List.of())));
        return Map.copyOf(result);
    }
}
