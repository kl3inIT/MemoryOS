package io.memoryos.document;

import io.memoryos.shared.TenantId;
import java.util.UUID;

/** Published inside the transaction changing the current document. */
public record DocumentChanged(TenantId tenantId, DocumentId documentId, UUID generation, boolean removed) { }
