package io.memoryos.connector;

import io.memoryos.iam.identity.ActorId;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface SharePointCredentialService {
    /** Verifies the draft with Microsoft before anything is stored. */
    CredentialId create(ActorId actorId, Draft draft);

    /** Replaces the client secret or certificate, fencing every Source that uses the credential. */
    long replaceAuthentication(ActorId actorId, CredentialId credentialId, long expectedRevision, Draft draft);

    void rename(ActorId actorId, CredentialId credentialId, long expectedRevision, String name);

    void delete(ActorId actorId, CredentialId credentialId, long expectedRevision);

    List<CredentialView> list(ActorId actorId);

    /** Re-checks a stored credential against Microsoft and records the resolved Tenant host. */
    TestResult test(ActorId actorId, CredentialId credentialId);

    record Draft(String name, UUID directoryId, UUID clientId, SharePointProvider.Cloud cloud,
                 SharePointProvider.AuthMethod authMethod, byte @Nullable [] clientSecret,
                 byte @Nullable [] pkcs12, char @Nullable [] pkcs12Password) implements AutoCloseable {
        public Draft {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(directoryId, "directoryId");
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(cloud, "cloud");
            Objects.requireNonNull(authMethod, "authMethod");
            clientSecret = clientSecret == null ? null : clientSecret.clone();
            pkcs12 = pkcs12 == null ? null : pkcs12.clone();
            pkcs12Password = pkcs12Password == null ? null : pkcs12Password.clone();
        }

        @Override public byte @Nullable [] clientSecret() { return clientSecret == null ? null : clientSecret.clone(); }
        @Override public byte @Nullable [] pkcs12() { return pkcs12 == null ? null : pkcs12.clone(); }
        @Override public char @Nullable [] pkcs12Password() { return pkcs12Password == null ? null : pkcs12Password.clone(); }

        @Override public void close() {
            if (clientSecret != null) Arrays.fill(clientSecret, (byte) 0);
            if (pkcs12 != null) Arrays.fill(pkcs12, (byte) 0);
            if (pkcs12Password != null) Arrays.fill(pkcs12Password, '\0');
        }

        @Override public String toString() { return "SharePointDraft[redacted]"; }
    }

    record CredentialView(CredentialId id, String name, UUID directoryId, UUID clientId, String cloud,
                          String authMethod, String status, @Nullable String certificateThumbprint,
                          @Nullable Instant certificateNotAfter, @Nullable String tenantHost, long credentialRevision,
                          Instant createdAt, Instant updatedAt, long sourceCount, List<String> actions) {
        public CredentialView { actions = List.copyOf(actions); }
    }

    /**
     * {@code allSitesReadable} is false when the token works but the application cannot read the whole
     * Tenant, which is expected for {@code Sites.Selected} and rules out the "All sites" scope.
     */
    record TestResult(boolean allSitesReadable, @Nullable String tenantHost) {}
}
