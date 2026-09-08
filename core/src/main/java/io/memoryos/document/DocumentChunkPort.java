package io.memoryos.document;

import io.memoryos.iam.TenantId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface DocumentChunkPort {
    Optional<DocumentChunkSet> prepare(TenantId tenantId, DocumentId documentId, UUID generation);
    boolean markSearchReady(TenantId tenantId, DocumentId documentId, UUID generation, String indexIdentity);
    List<DocumentIndexState> scan(String indexIdentity, String after, int limit);
    Map<UUID, UUID> currentGenerations(TenantId tenant, List<UUID> documents, String readyIdentity);
    Optional<DocumentChunkSet> read(TenantId tenant, DocumentId document, UUID generation);
    boolean isCurrent(TenantId tenantId, DocumentId documentId, UUID generation, String indexIdentity);
    void markSearchPending(TenantId tenantId, DocumentId documentId, UUID generation);
    void markSearchFailed(TenantId tenantId, DocumentId documentId, UUID generation);
}
