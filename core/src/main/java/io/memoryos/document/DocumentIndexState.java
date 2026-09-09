package io.memoryos.document;

import io.memoryos.iam.TenantId;
import java.util.UUID;

public record DocumentIndexState(TenantId tenantId, DocumentId documentId, UUID generation,
        int chunkCount, boolean ready) {
    public String cursor() { return tenantId.value() + ":" + documentId.value(); }
}
