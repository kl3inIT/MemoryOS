package io.memoryos.document;

import io.memoryos.shared.TenantId;
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
    /** Withdraws readiness of the generation in that index before it is rewritten there. */
    void markSearchPending(TenantId tenantId, DocumentId documentId, UUID generation, String indexIdentity);
    void markSearchFailed(TenantId tenantId, DocumentId documentId, UUID generation, String errorCode, String indexIdentity);
    /**
     * Makes the index the served one: the served generation on each document becomes the one ready in that index.
     * Runs in the transaction that makes the index's search generation PRESENT.
     */
    void serve(String indexIdentity);
}
