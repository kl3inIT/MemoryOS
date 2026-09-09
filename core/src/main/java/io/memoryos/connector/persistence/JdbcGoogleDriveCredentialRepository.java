package io.memoryos.connector.persistence;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAuthorizationService.CredentialView;
import io.memoryos.connector.GoogleDriveAuthorizationService.Grant;
import io.memoryos.connector.GoogleDriveAuthorizationService.Preparation;
import io.memoryos.connector.GoogleDriveOAuthClient;
import io.memoryos.connector.GoogleDriveConnectionService.State;
import io.memoryos.connector.GoogleDriveException;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceStatus;
import io.memoryos.identity.ActorId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import io.memoryos.tenant.TenantId;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcGoogleDriveCredentialRepository {
    private static final String SELECT = """
            SELECT google.*, credential.status AS credential_status, tenant.status AS tenant_status
            FROM credentials credential
            JOIN google_drive_credentials google ON google.tenant_id = credential.tenant_id AND google.credential_id = credential.id
            JOIN tenants tenant ON tenant.id = credential.tenant_id
            WHERE credential.tenant_id = :tenantId AND credential.id = :credentialId
              AND credential.credential_kind = 'GOOGLE_OAUTH'
            """;
    private final JdbcClient jdbc;
    private final JdbcSourceRepository sources;
    private final GoogleDriveCredentialConfiguration encryption;
    private final JdbcSourceDocumentRepository documents;
    private final JdbcSourceSyncRepository sync;

    public JdbcGoogleDriveCredentialRepository(JdbcClient jdbc, JdbcSourceRepository sources,
            GoogleDriveCredentialConfiguration encryption, JdbcSourceDocumentRepository documents,
            JdbcSourceSyncRepository sync) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.encryption = encryption;
        this.documents = documents;
        this.sync = sync;
    }

    public void requireConfigured() { encryption.cipher(); }

    public CredentialId create(TenantId tenantId, String name, Grant grant, GoogleDriveOAuthClient oauthClient) {
        if (!sources.lockActiveTenant(tenantId)) throw SourceException.notOwner();
        UUID credentialId = UUID.randomUUID();
        byte[] token = grant.refreshToken();
        GoogleDriveCredentialCipher.EncryptedCredential encrypted;
        try { encrypted = encryption.cipher().encrypt(tenantId, credentialId, token); }
        finally { Arrays.fill(token, (byte) 0); }
        var encryptedClient = encryptClient(tenantId, credentialId, oauthClient);
        jdbc.sql("INSERT INTO credentials (id, tenant_id, name, credential_kind, status) VALUES (:id, :tenant, :name, 'GOOGLE_OAUTH', 'ACTIVE')")
                .param("id", credentialId).param("tenant", tenantId.value()).param("name", name).update();
        jdbc.sql("""
                INSERT INTO google_drive_credentials (tenant_id, credential_id, account_subject, account_email,
                    granted_scopes, connection_status, refresh_token_ciphertext, refresh_token_nonce, key_version,
                    oauth_client_ciphertext, oauth_client_nonce, oauth_client_key_version)
                VALUES (:tenant, :credential, :subject, :email, :scopes, 'ACTIVE', :ciphertext, :nonce, :version,
                    :clientCiphertext, :clientNonce, :clientVersion)
                """).param("tenant", tenantId.value()).param("credential", credentialId)
                .param("subject", grant.accountSubject()).param("email", grant.accountEmail().toLowerCase(Locale.ROOT))
                .param("scopes", String.join(" ", new TreeSet<>(grant.scopes())))
                .param("ciphertext", encrypted.ciphertext()).param("nonce", encrypted.nonce())
                .param("version", encrypted.keyVersion()).param("clientCiphertext", encryptedClient.ciphertext())
                .param("clientNonce", encryptedClient.nonce()).param("clientVersion", encryptedClient.keyVersion()).update();
        return new CredentialId(credentialId);
    }

    public List<CredentialView> list(TenantId tenantId) {
        return jdbc.sql("""
                SELECT c.id, c.name, g.account_email, g.connection_status, g.credential_revision,
                  g.oauth_client_ciphertext IS NOT NULL AS configured, c.created_at, c.updated_at,
                  (SELECT COUNT(*) FROM connector_credential_pairs p
                   WHERE p.tenant_id = c.tenant_id AND p.credential_id = c.id) AS source_count
                FROM credentials c
                JOIN google_drive_credentials g ON g.tenant_id = c.tenant_id AND g.credential_id = c.id
                WHERE c.tenant_id = :tenant AND c.credential_kind = 'GOOGLE_OAUTH'
                ORDER BY c.created_at, c.id
                """).param("tenant", tenantId.value()).query((r, _) -> new CredentialView(
                        new CredentialId(r.getObject("id", UUID.class)), r.getString("name"),
                        r.getString("account_email"), r.getString("connection_status"), r.getLong("credential_revision"),
                        r.getBoolean("configured"), r.getTimestamp("created_at").toInstant(),
                        r.getTimestamp("updated_at").toInstant(), r.getLong("source_count"))).list();
    }

    public void delete(TenantId tenantId, CredentialId credentialId, long expectedRevision) {
        var row = lock(tenantId, credentialId).orElseThrow(SourceException::notFound);
        requireRevision(row, expectedRevision);
        if (!attachedSources(tenantId, credentialId).isEmpty()) {
            throw SourceException.conflict("Google credential is still used by Sources");
        }
        jdbc.sql("DELETE FROM credentials WHERE tenant_id = :tenant AND id = :credential")
                .param("tenant", tenantId.value()).param("credential", credentialId.value()).update();
    }

    public CredentialId credentialId(TenantId tenantId, SourceId sourceId) {
        return jdbc.sql("""
                SELECT p.credential_id FROM connector_credential_pairs p
                JOIN connectors c ON c.tenant_id = p.tenant_id AND c.id = p.connector_id
                WHERE p.tenant_id = :tenant AND p.id = :source AND p.access_type = 'RESTRICTED'
                  AND c.connector_type = 'GOOGLE_DRIVE' AND c.status = 'ACTIVE' AND p.status <> 'DELETING'
                """).param("tenant", tenantId.value()).param("source", sourceId.value())
                .query((r, _) -> new CredentialId(r.getObject("credential_id", UUID.class)))
                .optional().orElseThrow(SourceException::notFound);
    }

    public State state(TenantId tenantId, SourceId sourceId) {
        var id = credentialId(tenantId, sourceId);
        var row = read(tenantId, id, false).orElseThrow(SourceException::notFound);
        return new State(id, row.email(), row.status(), row.revision(), row.oauthClientConfigured());
    }

    public Stored readUsable(TenantId tenantId, CredentialId credentialId) {
        var row = read(tenantId, credentialId, false).orElseThrow(SourceException::notFound);
        if (!row.usable()) throw GoogleDriveException.needsReauthorization();
        return row;
    }

    public Optional<Stored> lock(TenantId tenantId, CredentialId credentialId) {
        if (!sources.lockActiveTenant(tenantId)) return Optional.empty();
        // Credential authority precedes every attached Source lock.
        jdbc.sql("SELECT id FROM credentials WHERE tenant_id = :tenant AND id = :credential FOR UPDATE")
                .param("tenant", tenantId.value()).param("credential", credentialId.value()).query(UUID.class).optional();
        return read(tenantId, credentialId, true);
    }

    public Optional<Stored> lockSource(TenantId tenantId, SourceId sourceId) {
        try {
            var id = credentialId(tenantId, sourceId);
            var row = lock(tenantId, id);
            if (row.isEmpty()) return row;
            if (sources.lock(tenantId, sourceId).status() == SourceStatus.DELETING) return Optional.empty();
            return row;
        } catch (SourceException exception) {
            if ("SOURCE_NOT_FOUND".equals(exception.code())) return Optional.empty();
            throw exception;
        }
    }

    private Optional<Stored> read(TenantId tenantId, CredentialId credentialId, boolean lock) {
        return jdbc.sql(SELECT + (lock ? " FOR UPDATE OF google" : ""))
                .param("tenantId", tenantId.value()).param("credentialId", credentialId.value())
                .query((row, ignored) -> new Stored(row.getObject("credential_id", UUID.class),
                        row.getString("account_subject"), row.getString("account_email"),
                        row.getString("connection_status"), row.getLong("credential_revision"), row.getLong("payload_revision"),
                        row.getBytes("refresh_token_ciphertext"), row.getBytes("refresh_token_nonce"), row.getString("key_version"),
                        row.getBytes("oauth_client_ciphertext"), row.getBytes("oauth_client_nonce"),
                        row.getString("oauth_client_key_version"),
                        "ACTIVE".equals(row.getString("connection_status")) && "ACTIVE".equals(row.getString("credential_status"))
                                && "ACTIVE".equals(row.getString("tenant_status"))))
                .optional();
    }

    public byte[] decrypt(TenantId tenantId, Stored row) {
        if (row.ciphertext() == null || row.nonce() == null || row.keyVersion() == null) throw GoogleDriveException.needsReauthorization();
        try {
            return encryption.cipher().decrypt(tenantId, row.credentialId(),
                    new GoogleDriveCredentialCipher.EncryptedCredential(row.ciphertext(), row.nonce(), row.keyVersion()));
        } catch (IllegalStateException exception) { throw GoogleDriveException.notConfigured(); }
    }

    public long reauthorize(TenantId tenantId, CredentialId credentialId, String name, long expectedRevision, Grant grant, GoogleDriveOAuthClient oauthClient) {
        var row = lock(tenantId, credentialId).orElseThrow(SourceException::notFound);
        requireRevision(row, expectedRevision);
        if (!row.subject().equals(grant.accountSubject())) throw SourceException.conflict("Google ingestion account changed");
        byte[] token = grant.refreshToken();
        GoogleDriveCredentialCipher.EncryptedCredential encrypted;
        try { encrypted = encryption.cipher().encrypt(tenantId, row.credentialId(), token); }
        finally { Arrays.fill(token, (byte) 0); }
        var encryptedClient = encryptClient(tenantId, row.credentialId(), oauthClient);
        jdbc.sql("""
                UPDATE google_drive_credentials SET account_email = :email, granted_scopes = :scopes,
                    connection_status = 'ACTIVE', credential_revision = credential_revision + 1,
                    oauth_client_ciphertext = :clientCiphertext, oauth_client_nonce = :clientNonce,
                    oauth_client_key_version = :clientVersion, refresh_token_ciphertext = :ciphertext,
                    refresh_token_nonce = :nonce, key_version = :version, payload_revision = payload_revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND credential_id = :credential
                """).param("email", grant.accountEmail().toLowerCase(Locale.ROOT))
                .param("scopes", String.join(" ", new TreeSet<>(grant.scopes())))
                .param("clientCiphertext", encryptedClient.ciphertext()).param("clientNonce", encryptedClient.nonce())
                .param("clientVersion", encryptedClient.keyVersion())
                .param("ciphertext", encrypted.ciphertext()).param("nonce", encrypted.nonce()).param("version", encrypted.keyVersion())
                .param("tenant", tenantId.value()).param("credential", row.credentialId()).update();
        status(tenantId, row, "ACTIVE");
        jdbc.sql("UPDATE credentials SET name = :name WHERE tenant_id = :tenant AND id = :credential")
                .param("name", name).param("tenant", tenantId.value()).param("credential", credentialId.value()).update();
        invalidateSources(tenantId, credentialId);
        return expectedRevision + 1;
    }

    public boolean rotate(TenantId tenantId, CredentialId credentialId, long expectedRevision, long expectedPayloadRevision, byte[] token) {
        var row = lock(tenantId, credentialId).orElse(null);
        if (row == null || !row.usable() || row.revision() != expectedRevision || row.payloadRevision() != expectedPayloadRevision) return false;
        replaceToken(tenantId, row, token);
        return true;
    }

    private void replaceToken(TenantId tenantId, Stored row, byte[] token) {
        var encrypted = encryption.cipher().encrypt(tenantId, row.credentialId(), token);
        int changed = jdbc.sql("""
                UPDATE google_drive_credentials SET refresh_token_ciphertext = :ciphertext,
                    refresh_token_nonce = :nonce, key_version = :version, payload_revision = payload_revision + 1,
                    connection_status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND credential_id = :credential AND payload_revision = :expected
                """).param("ciphertext", encrypted.ciphertext()).param("nonce", encrypted.nonce())
                .param("version", encrypted.keyVersion()).param("tenant", tenantId.value())
                .param("credential", row.credentialId()).param("expected", row.payloadRevision()).update();
        if (changed != 1) throw SourceException.conflict("Google credential changed concurrently");
        jdbc.sql("UPDATE credentials SET updated_at = CURRENT_TIMESTAMP WHERE tenant_id = :tenant AND id = :credential")
                .param("tenant", tenantId.value()).param("credential", row.credentialId()).update();
    }

    public boolean authenticationFailed(TenantId tenantId, CredentialId credentialId, long expectedRevision) {
        var row = lock(tenantId, credentialId).orElse(null);
        if (row == null || !row.usable() || row.revision() != expectedRevision) return false;
        markAuthenticationFailed(tenantId, row);
        return true;
    }

    public boolean refreshFailed(TenantId tenantId, CredentialId credentialId, long expectedRevision, long expectedPayloadRevision) {
        var row = lock(tenantId, credentialId).orElse(null);
        if (row == null || !row.usable() || row.revision() != expectedRevision || row.payloadRevision() != expectedPayloadRevision) return false;
        markAuthenticationFailed(tenantId, row);
        return true;
    }

    private void markAuthenticationFailed(TenantId tenantId, Stored row) {
        jdbc.sql("""
                UPDATE google_drive_credentials SET connection_status = 'NEEDS_REAUTHORIZATION',
                    credential_revision = credential_revision + 1,
                    payload_revision = payload_revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND credential_id = :credential
                    AND credential_revision = :revision AND payload_revision = :payloadRevision
                """).param("tenant", tenantId.value()).param("credential", row.credentialId())
                .param("revision", row.revision()).param("payloadRevision", row.payloadRevision()).update();
        status(tenantId, row, "NEEDS_REAUTHORIZATION");
        invalidateSources(tenantId, new CredentialId(row.credentialId()));
    }

    public byte[] disconnect(TenantId tenantId, CredentialId credentialId, long expectedRevision) {
        var row = lock(tenantId, credentialId).orElseThrow(SourceException::notFound);
        requireRevision(row, expectedRevision);
        byte[] token = new byte[0];
        if (!"REVOKED".equals(row.status())) {
            try { token = decrypt(tenantId, row); }
            catch (GoogleDriveException ignored) { /* Destroy local authority even if its encryption key is unavailable. */ }
        }
        try {
            jdbc.sql("""
                    UPDATE google_drive_credentials SET connection_status = 'REVOKED', refresh_token_ciphertext = NULL,
                        refresh_token_nonce = NULL, key_version = NULL, credential_revision = credential_revision + 1,
                        payload_revision = payload_revision + 1,
                        updated_at = CURRENT_TIMESTAMP WHERE tenant_id = :tenant AND credential_id = :credential
                    """).param("tenant", tenantId.value()).param("credential", row.credentialId()).update();
            status(tenantId, row, "REVOKED");
            invalidateSources(tenantId, credentialId);
        } catch (RuntimeException exception) {
            Arrays.fill(token, (byte) 0);
            throw exception;
        }
        return token;
    }

    private void status(TenantId tenantId, Stored row, String status) {
        jdbc.sql("UPDATE credentials SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = :tenant AND id = :id")
                .param("status", status).param("tenant", tenantId.value()).param("id", row.credentialId()).update();
    }

    private List<SourceId> attachedSources(TenantId tenantId, CredentialId credentialId) {
        return jdbc.sql("""
                SELECT id FROM connector_credential_pairs
                WHERE tenant_id = :tenant AND credential_id = :credential ORDER BY id
                """).param("tenant", tenantId.value()).param("credential", credentialId.value())
                .query((r, _) -> new SourceId(r.getObject("id", UUID.class))).list();
    }

    private void invalidateSources(TenantId tenantId, CredentialId credentialId) {
        var attached = attachedSources(tenantId, credentialId);
        for (var source : attached) sources.lock(tenantId, source);
        for (var source : attached) sync.cancel(tenantId, source);
        jdbc.sql("""
                UPDATE index_attempts a SET status = 'CANCELLED', claim_token = NULL,
                    lease_expires_at = NULL, completed_at = CURRENT_TIMESTAMP
                FROM connector_credential_pairs p
                WHERE a.tenant_id = :tenant AND p.tenant_id = a.tenant_id
                  AND p.id = a.connector_credential_pair_id AND p.credential_id = :credential
                  AND a.status IN ('NOT_STARTED','IN_PROGRESS')
                """).param("tenant", tenantId.value()).param("credential", credentialId.value()).update();
        for (var source : attached) {
            documents.invalidateSource(tenantId, source);
            jdbc.sql("UPDATE google_drive_membership SET eligible = FALSE WHERE tenant_id = :tenant AND source_id = :source")
                    .param("tenant", tenantId.value()).param("source", source.value()).update();
            jdbc.sql("""
                    UPDATE google_drive_sources SET next_sync_at = CURRENT_TIMESTAMP, error_code = NULL,
                      discovery_revision = discovery_revision + CASE WHEN scope_mode = 'SPECIFIC' THEN 1 ELSE 0 END,
                      discovered_at = NULL, discovery_scope_revision = NULL, discovery_credential_revision = NULL
                    WHERE tenant_id = :tenant AND source_id = :source
                    """).param("tenant", tenantId.value()).param("source", source.value()).update();
            jdbc.sql("DELETE FROM google_drive_link_origins WHERE tenant_id = :tenant AND source_id = :source")
                    .param("tenant", tenantId.value()).param("source", source.value()).update();
            jdbc.sql("DELETE FROM google_drive_discovery_errors WHERE tenant_id = :tenant AND source_id = :source")
                    .param("tenant", tenantId.value()).param("source", source.value()).update();
            jdbc.sql("""
                    DELETE FROM google_drive_linked_documents d WHERE d.tenant_id = :tenant AND d.source_id = :source
                    AND NOT EXISTS (SELECT 1 FROM google_drive_link_approvals a
                      WHERE a.tenant_id = d.tenant_id AND a.source_id = d.source_id AND a.file_id = d.file_id)
                    """).param("tenant", tenantId.value()).param("source", source.value()).update();
            jdbc.sql("""
                    UPDATE google_drive_linked_documents SET status = 'UNAVAILABLE'
                    WHERE tenant_id = :tenant AND source_id = :source
                    """).param("tenant", tenantId.value()).param("source", source.value()).update();
            sources.recomputeStatus(tenantId, source, false);
        }
    }


    public GoogleDriveOAuthClient oauthClient(TenantId tenantId, Stored row) {
        if (!row.oauthClientConfigured()) throw GoogleDriveException.oauthClientRequired();
        byte[] payload;
        try {
            payload = encryption.cipher().decrypt(tenantId, row.credentialId(), "oauth-client",
                    new GoogleDriveCredentialCipher.EncryptedCredential(row.clientCiphertext(), row.clientNonce(), row.clientKeyVersion()));
        } catch (IllegalStateException exception) { throw GoogleDriveException.notConfigured(); }
        try { return GoogleDriveOAuthClient.decode(payload); }
        finally { Arrays.fill(payload, (byte) 0); }
    }

    private GoogleDriveCredentialCipher.EncryptedCredential encryptClient(TenantId tenantId, UUID credentialId, GoogleDriveOAuthClient client) {
        byte[] payload = client.encode();
        try { return encryption.cipher().encrypt(tenantId, credentialId, "oauth-client", payload); }
        finally { Arrays.fill(payload, (byte) 0); }
    }

    public String snapshot(ActorId actorId, Preparation preparation, GoogleDriveOAuthClient client) {
        byte[] payload = client.encode();
        try {
            var encrypted = encryption.cipher().encrypt(preparation.tenantId(), preparation.consentId(),
                    consentPurpose(actorId, preparation), payload);
            var encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(encrypted.keyVersion().getBytes(StandardCharsets.UTF_8)) + "."
                    + encoder.encodeToString(encrypted.nonce()) + "." + encoder.encodeToString(encrypted.ciphertext());
        } finally { Arrays.fill(payload, (byte) 0); }
    }

    public GoogleDriveOAuthClient snapshot(ActorId actorId, Preparation preparation) {
        byte[] payload;
        try {
            if (preparation.oauthClientSnapshot().length() > 8192) throw GoogleDriveException.invalidOAuthClient();
            String[] parts = preparation.oauthClientSnapshot().split("\\.", -1);
            if (parts.length != 3) throw GoogleDriveException.invalidOAuthClient();
            var decoder = Base64.getUrlDecoder();
            var encrypted = new GoogleDriveCredentialCipher.EncryptedCredential(decoder.decode(parts[2]),
                    decoder.decode(parts[1]), new String(decoder.decode(parts[0]), StandardCharsets.UTF_8));
            payload = encryption.cipher().decrypt(preparation.tenantId(), preparation.consentId(),
                    consentPurpose(actorId, preparation), encrypted);
        } catch (IllegalArgumentException | IllegalStateException exception) { throw GoogleDriveException.invalidOAuthClient(); }
        try { return GoogleDriveOAuthClient.decode(payload); }
        finally { Arrays.fill(payload, (byte) 0); }
    }

    private static String consentPurpose(ActorId actorId, Preparation preparation) {
        return "oauth-consent|" + actorId.value() + "|" + preparation.credentialId() + "|"
                + preparation.expectedRevision() + "|" + preparation.name();
    }
    private static void requireRevision(Stored row, long expectedRevision) {
        if (expectedRevision < 1 || row.revision() != expectedRevision) throw SourceException.conflict("Google credential revision is stale");
    }

    public record Stored(UUID credentialId, String subject, String email, String status, long revision, long payloadRevision,
            byte @Nullable [] ciphertext, byte @Nullable [] nonce, @Nullable String keyVersion,
            byte @Nullable [] clientCiphertext, byte @Nullable [] clientNonce, @Nullable String clientKeyVersion, boolean usable) {
        public boolean oauthClientConfigured() { return clientCiphertext != null && clientNonce != null && clientKeyVersion != null; }
        @Override public boolean usable() { return usable && ciphertext != null && nonce != null && keyVersion != null && oauthClientConfigured(); }
        @Override public String toString() { return "StoredGoogleCredential[redacted]"; }
    }
}
