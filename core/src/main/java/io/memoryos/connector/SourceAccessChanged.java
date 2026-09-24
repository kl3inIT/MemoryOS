package io.memoryos.connector;

import io.memoryos.shared.TenantId;

/**
 * Published inside the transaction that replaces a Source's Group grants or changes its access type, so the
 * search projection can refresh the access fields of the Source's indexed documents atomically with the change.
 */
public record SourceAccessChanged(TenantId tenantId, SourceId sourceId) {
}
