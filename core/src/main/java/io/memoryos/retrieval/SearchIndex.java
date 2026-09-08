package io.memoryos.retrieval;

import io.memoryos.document.DocumentChunkSet;
import io.memoryos.document.DocumentId;
import io.memoryos.document.DocumentIndexState;
import io.memoryos.iam.TenantId;

/** Ingestion's public entry point to the search projection. */
public interface SearchIndex {
    String identity();
    void index(DocumentChunkSet document);
    void delete(TenantId tenantId, DocumentId documentId);
    boolean contains(DocumentIndexState document);
    void purgeStale();
}
