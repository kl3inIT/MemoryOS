package io.memoryos.connector;

import io.memoryos.organization.OrganizationId;


import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record CleanupWork(
        SourceOperationId operationId,
        OrganizationId organizationId,
        SourceOperationType type,
        UUID sourceId,
        @Nullable SourceItemId itemId,
        UUID claimToken
) {
}
