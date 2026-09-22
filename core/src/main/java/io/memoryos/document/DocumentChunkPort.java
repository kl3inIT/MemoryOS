package io.memoryos.document;

import io.memoryos.iam.tenant.TenantId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface DocumentChunkPort {
    Optional<DocumentChunkSet> prepare(TenantId tenantId, DocumentId documentId, UUID generation);
    boolean markSearchReady(TenantId tenantId, DocumentId documentId, UUID generation, String indexIdentity);
    List<DocumentIndexState> scan(String indexIdentity, String after, int limit);
    /** Served generations under the identity, or current content generations when the identity is blank. */
    Map<UUID, UUID> currentGenerations(TenantId tenant, List<UUID> documents, String readyIdentity);
    /** Generations whose chunks must survive cleanup: the served generation and the content generation being indexed. */
    Map<UUID, Set<UUID>> retainedGenerations(TenantId tenant, List<UUID> documents);
    Optional<DocumentChunkSet> read(TenantId tenant, DocumentId document, UUID generation);
    boolean isCurrent(TenantId tenantId, DocumentId documentId, UUID generation, String indexIdentity);
    void markSearchPending(TenantId tenantId, DocumentId documentId, UUID generation);
    void markSearchFailed(TenantId tenantId, DocumentId documentId, UUID generation, String errorCode);
}
