package io.memoryos.connector;

import io.memoryos.document.DocumentId;
import io.memoryos.shared.TenantId;
import java.util.List;

/**
 * Published in the transaction that records a changed observation of a provider file's permissions, whatever the
 * provider. Listeners treat it as a hint to re-read the permissions of {@code documentIds}; the event carries no
 * permission payload.
 *
 * @param revision the revision of the provider's retained observation that changed
 */
public record SourceAclChanged(
        TenantId tenantId,
        SourceId sourceId,
        String providerFileId,
        List<DocumentId> documentIds,
        long revision) {
    public SourceAclChanged {
        documentIds = List.copyOf(documentIds);
    }
}
