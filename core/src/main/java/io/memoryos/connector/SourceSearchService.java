package io.memoryos.connector;

import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.document.DocumentId;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantAccessResolver;
import io.memoryos.iam.TenantId;
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
        return new SourceSearchScope(tenant, documents.searchableSources(tenant));
    }

    public Map<UUID, List<DocumentSourceMetadata>> readableMetadata(SourceSearchScope scope, List<UUID> ids) {
        return documents.sourceMetadata(scope.tenant(), ids, true, null).entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> entry.getValue().stream().filter(m -> scope.sources().containsKey(m.sourceId())).toList()));
    }

    public List<DocumentSourceMetadata> indexMetadata(TenantId tenant, DocumentId document, UUID generation) {
        return documents.sourceMetadata(tenant, List.of(document.value()), false, generation)
                .getOrDefault(document.value(), List.of());
    }
}
