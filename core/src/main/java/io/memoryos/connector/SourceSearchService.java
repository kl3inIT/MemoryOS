package io.memoryos.connector;

import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantAccessResolver;
import io.memoryos.iam.tenant.TenantId;
import java.util.List;
import java.util.Map;
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
    public java.util.Set<String> accessTokens(TenantId tenant, ActorId actor) {
        return documents.actorAccessTokens(tenant, actor);
    }

    public DocumentAccess indexAccess(TenantId tenant, DocumentId document) {
        return documents.documentAccess(tenant, document.value());
    }

    public record SourceOption(UUID id, String name, SourceType type) {}

    public List<SourceOption> options(ActorId actor, int offset, int limit) {
        if (offset < 0 || offset > 10000 || limit < 1 || limit > 100) throw SourceException.invalid("Invalid source page", "source option page out of bounds");
        var tenant = tenants.findActiveTenant(actor).orElseThrow(SourceException::notFound);
        return documents.searchableSourceOptions(tenant, actor, offset, limit);
    }

    /** Tenant-scoped Source names regardless of the reader's Source authority; names never grant search access. */
    public List<SourceOption> names(TenantId tenant, java.util.Collection<UUID> ids) {
        if (ids.size() > 500) throw SourceException.invalid("Invalid source page", "source name lookup out of bounds");
        return ids.isEmpty() ? List.of() : documents.sourceNames(tenant, ids);
    }

    public Map<UUID, List<DocumentSourceMetadata>> readableMetadata(SourceSearchScope scope, List<UUID> ids) {
        if (tenants.findActiveTenant(scope.actor()).filter(scope.tenant()::equals).isEmpty()) return Map.of();
        var metadata = new java.util.LinkedHashMap<UUID, List<DocumentSourceMetadata>>();
        documents.sourceMetadata(scope.tenant(), ids, scope.actor(), null).forEach((document, origins) -> {
            var visible = origins.stream().filter(origin -> scope.sources().containsKey(origin.sourceId())).toList();
            if (!visible.isEmpty()) metadata.put(document, visible);
        });
        return Map.copyOf(metadata);
    }

    /** Original PDF object for presentation; callers still check Document eligibility and generation. */
    /** Original source objects of any media type for readable, eligible Documents; callers still check generation. */
    public java.util.Map<UUID, io.memoryos.objectstorage.StoredObjectReference> originals(TenantId tenant, ActorId actor,
            java.util.Set<UUID> documents) {
        return documents.isEmpty() ? java.util.Map.of() : this.documents.originals(tenant, actor, documents);
    }

    public List<DocumentSourceMetadata> indexMetadata(TenantId tenant, DocumentId document, UUID generation) {
        return documents.sourceMetadata(tenant, List.of(document.value()), null, generation)
                .getOrDefault(document.value(), List.of());
    }
}
