package io.memoryos.connector;

import io.memoryos.iam.TenantId;

public interface GoogleDriveConnectionService {
    State state(TenantId tenantId, SourceId sourceId);
    Connection open(TenantId tenantId, SourceId sourceId);
    Connection openCredential(TenantId tenantId, CredentialId credentialId);
    boolean currentCredential(TenantId tenantId, CredentialId credentialId, long credentialRevision);
    void authenticationFailedCredential(TenantId tenantId, CredentialId credentialId, long credentialRevision);
    boolean current(TenantId tenantId, SourceId sourceId, long credentialRevision);
    void authenticationFailed(TenantId tenantId, SourceId sourceId, long credentialRevision);

    record State(CredentialId credentialId, String accountEmail, String status, long credentialRevision, boolean oauthClientConfigured) {}
    record Connection(GoogleDriveProvider.Session session, long credentialRevision) implements AutoCloseable {
        @Override public void close() { session.close(); }
    }
}
