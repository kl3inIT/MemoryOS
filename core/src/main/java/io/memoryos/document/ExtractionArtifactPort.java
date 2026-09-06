package io.memoryos.document;

import io.memoryos.tenant.TenantId;
import java.util.UUID;

public interface ExtractionArtifactPort {
    void pinProfile(TenantId tenantId, UUID operationId, String profile);

    DocumentContent stage(TenantId tenantId, UUID operationId, String profile, DocumentContent content);

    int cleanup();
}
