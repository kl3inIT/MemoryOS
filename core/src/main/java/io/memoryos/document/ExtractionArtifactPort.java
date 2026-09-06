package io.memoryos.document;

import io.memoryos.tenant.TenantId;

public interface ExtractionArtifactPort {
    DocumentContent stage(TenantId tenantId, DocumentContent content);

    int cleanup();
}
