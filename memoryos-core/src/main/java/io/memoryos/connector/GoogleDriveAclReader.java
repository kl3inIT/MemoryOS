package io.memoryos.connector;

import io.memoryos.document.DocumentId;
import io.memoryos.shared.TenantId;
import java.util.List;

/**
 * System-level read of retained Google Drive ACL observations for the enforcement capability.
 * Returns one snapshot per (Source, file) the Document is mapped to; empty when the Document has
 * no Google Drive mapping. Observations are not effective authorization — callers must evaluate
 * status, contextStatus, lastSuccess and their own freshness policy.
 */
public interface GoogleDriveAclReader {
    List<GoogleDriveAclSnapshot> readByDocument(TenantId tenantId, DocumentId documentId);
}
