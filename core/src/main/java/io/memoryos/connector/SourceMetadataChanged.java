package io.memoryos.connector;

import io.memoryos.document.DocumentId;
import io.memoryos.shared.TenantId;
import java.util.List;

/**
 * Published in the transaction that records changed provider dates for an item whose content was not re-read, so
 * the search projection can refresh the source metadata of {@code documentIds}; the event carries no metadata.
 */
public record SourceMetadataChanged(TenantId tenantId, SourceId sourceId, List<DocumentId> documentIds) {
    public SourceMetadataChanged {
        documentIds = List.copyOf(documentIds);
    }
}
