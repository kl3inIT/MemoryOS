package io.memoryos.document;

import io.memoryos.tenant.TenantId;

import java.util.List;

import org.jspecify.annotations.Nullable;

public interface DocumentCommandPort {

    DocumentId publish(
            TenantId tenantId,
            @Nullable DocumentId existingDocumentId,
            DocumentContent content,
            String sourceSha256
    );

    void removeUnreferenced(TenantId tenantId, List<DocumentId> documentIds);
}
