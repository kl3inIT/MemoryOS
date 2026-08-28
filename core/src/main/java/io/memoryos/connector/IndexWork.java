package io.memoryos.connector;

import io.memoryos.organization.OrganizationId;

import java.util.UUID;

public record IndexWork(
        SourceOperationId operationId,
        OrganizationId organizationId,
        UUID connectorId,
        SourceId sourceId,
        SourceItemId itemId,
        UUID itemVersionId,
        UUID claimToken,
        String filename,
        byte[] content,
        String sha256
) {
    public IndexWork {
        content = content.clone();
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
