package io.memoryos.connector;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface GoogleDriveAuthorizationService {
    Set<String> REQUIRED_SCOPES = Set.of("openid",
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/spreadsheets.readonly",
            "https://www.googleapis.com/auth/documents.readonly");

    Preparation prepare(ActorId actorId, String name, @Nullable CredentialId credentialId, @Nullable Long expectedRevision,
                        @Nullable GoogleDriveOAuthClient oauthClient);
    GoogleDriveOAuthClient oauthClient(ActorId actorId, Preparation preparation);
    CredentialId complete(ActorId actorId, Preparation preparation, Grant grant);
    byte[] disconnect(ActorId actorId, CredentialId credentialId, long expectedRevision);
    List<CredentialView> list(ActorId actorId);
    void delete(ActorId actorId, CredentialId credentialId, long expectedRevision);

    /** {@code accountEmail} is the connected account for OAuth and the acting primary admin for a service account. */
    record CredentialView(CredentialId id, String name, String accountEmail, String status,
                          long credentialRevision, String authMethod, @Nullable String serviceAccountEmail,
                          boolean oauthClientConfigured,
                          Instant createdAt, Instant updatedAt, long sourceCount, List<String> actions) {}

    record Preparation(TenantId tenantId, String name, @Nullable CredentialId credentialId, @Nullable Long expectedRevision,
                       UUID consentId, String oauthClientSnapshot) {
        @Override public @NonNull String toString() { return "GoogleDrivePreparation[redacted]"; }
    }
    record Grant(String accountSubject, String accountEmail, Set<String> scopes, byte[] refreshToken) implements AutoCloseable {
        public Grant { scopes = Set.copyOf(scopes); refreshToken = refreshToken.clone(); }
        @Override public byte[] refreshToken() { return refreshToken.clone(); }
        @Override public void close() { Arrays.fill(refreshToken, (byte) 0); }
        @Override public @NonNull String toString() { return "GoogleDriveGrant[redacted]"; }
    }
}
