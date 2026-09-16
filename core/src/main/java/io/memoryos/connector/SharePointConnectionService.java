package io.memoryos.connector;

import io.memoryos.iam.tenant.TenantId;

/** Opens a provider session from a stored SharePoint credential and reports the revision it was opened at. */
public interface SharePointConnectionService {
    State state(TenantId tenantId, SourceId sourceId);

    Connection open(TenantId tenantId, SourceId sourceId);

    Connection openCredential(TenantId tenantId, CredentialId credentialId);

    /** Marks the credential as needing an update after Microsoft rejected it. */
    void authenticationFailed(TenantId tenantId, CredentialId credentialId, long credentialRevision);

    record State(CredentialId credentialId, String name, String status, long credentialRevision,
                 @org.jspecify.annotations.Nullable String tenantHost) {}

    record Connection(SharePointProvider.Session session, long credentialRevision,
                      @org.jspecify.annotations.Nullable String tenantHost) implements AutoCloseable {
        @Override public void close() { session.close(); }
    }
}
