package io.memoryos.document;

import io.memoryos.shared.TenantId;
import java.util.UUID;

public record DocumentIndexState(TenantId tenantId, DocumentId documentId, UUID generation,
        int chunkCount, boolean ready) {
    /** Where a scan resumes: strictly after this document in (Tenant, Document) order. */
    public record Cursor(TenantId tenantId, DocumentId documentId) { }

    public Cursor cursor() { return new Cursor(tenantId, documentId); }
}
