package io.memoryos.connector;

import io.memoryos.iam.tenant.TenantId;

/** Validates that a provider-backed Source version still owns its captured credential revision. */
public interface ProviderAuthorityService {
    boolean current(SourceType sourceType, TenantId tenantId, SourceId sourceId, long credentialRevision);
}
