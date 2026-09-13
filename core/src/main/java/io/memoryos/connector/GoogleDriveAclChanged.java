package io.memoryos.connector;

import io.memoryos.document.DocumentId;
import io.memoryos.iam.tenant.TenantId;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Published in the same transaction that records a Google Drive ACL observation whenever the
 * retained permission payload or the attempt status changes. Listeners must treat this as a
 * hint to re-read the snapshot via {@link GoogleDriveAclReader}; the event carries no payload.
 */
public record GoogleDriveAclChanged(
        TenantId tenantId,
        SourceId sourceId,
        String fileId,
        List<DocumentId> documentIds,
        long revision,
        GoogleDriveAclSnapshot.Status status,
        @Nullable String errorCode) {
    public GoogleDriveAclChanged {
        documentIds = List.copyOf(documentIds);
    }
}
