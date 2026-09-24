package io.memoryos.connector.persistence;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.SharePointAuthentication;
import io.memoryos.connector.SharePointCertificate;
import io.memoryos.connector.SharePointCredentialService.CredentialView;
import io.memoryos.connector.SharePointException;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSharePointCredentialRepository {
    static final String CLIENT_SECRET = "client-secret";
    static final String PRIVATE_KEY = "private-key";
    private static final String SELECT = """
            SELECT sharepoint.*, credential.name, credential.owner_actor_id,
                   credential.status AS credential_status, tenant.status AS tenant_status
            FROM credentials credential
            JOIN sharepoint_credentials sharepoint
              ON sharepoint.tenant_id = credential.tenant_id AND sharepoint.credential_id = credential.id
            JOIN tenants tenant ON tenant.id = credential.tenant_id
            WHERE credential.tenant_id = :tenantId AND credential.id = :credentialId
              AND credential.credential_kind = 'SHAREPOINT_APP'
            """;

    private final JdbcClient jdbc;
    private final JdbcSourceRepository sources;
    private final SharePointCredentialConfiguration encryption;

    public JdbcSharePointCredentialRepository(JdbcClient jdbc, JdbcSourceRepository sources,
            SharePointCredentialConfiguration encryption) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.encryption = encryption;
    }

    public void requireConfigured() { encryption.cipher(); }

    public CredentialId create(TenantId tenantId, ActorId owner, String name, UUID directoryId, UUID clientId,
            SharePointProvider.Cloud cloud, SharePointAuthentication authentication, @Nullable String tenantHost) {
        if (!sources.lockActiveTenant(tenantId)) throw SourceException.notFound();
        UUID credentialId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO credentials (id, tenant_id, name, credential_kind, status, owner_actor_id)
                VALUES (:id, :tenant, :name, 'SHAREPOINT_APP', 'ACTIVE', :owner)
                """).param("id", credentialId).param("tenant", tenantId.value())
                .param("name", name).param("owner", owner.value()).update();
        var envelope = encrypt(tenantId, credentialId, authentication);
        jdbc.sql("""
                INSERT INTO sharepoint_credentials (tenant_id, credential_id, directory_id, client_id, cloud,
                    auth_method, connection_status, secret_ciphertext, secret_nonce, secret_key_version,
                    private_key_ciphertext, private_key_nonce, private_key_version, certificate_der,
                    certificate_thumbprint, certificate_not_after, tenant_host)
                VALUES (:tenant, :credential, :directory, :client, :cloud, :method, 'ACTIVE',
                    :secretCiphertext, :secretNonce, :secretVersion, :keyCiphertext, :keyNonce, :keyVersion,
                    :certificate, :thumbprint, :notAfter, :host)
                """).param("tenant", tenantId.value()).param("credential", credentialId)
                .param("directory", directoryId).param("client", clientId).param("cloud", cloud.name())
                .param("method", authentication.authMethod().name())
                .param("secretCiphertext", envelope.secretCiphertext()).param("secretNonce", envelope.secretNonce())
                .param("secretVersion", envelope.secretKeyVersion()).param("keyCiphertext", envelope.keyCiphertext())
                .param("keyNonce", envelope.keyNonce()).param("keyVersion", envelope.keyKeyVersion())
                .param("certificate", envelope.certificate()).param("thumbprint", envelope.thumbprint())
                .param("notAfter", envelope.notAfter()).param("host", tenantHost).update();
        return new CredentialId(credentialId);
    }

    public List<CredentialView> list(TenantId tenantId, @Nullable ActorId owner) {
        return jdbc.sql("""
                SELECT c.id, c.name, s.directory_id, s.client_id, s.cloud, s.auth_method, s.connection_status,
                  s.certificate_thumbprint, s.certificate_not_after, s.tenant_host, s.credential_revision,
                  c.created_at, c.updated_at,
                  (SELECT COUNT(*) FROM connector_credential_pairs p
                   WHERE p.tenant_id = c.tenant_id AND p.credential_id = c.id) AS source_count
                FROM credentials c
                JOIN sharepoint_credentials s ON s.tenant_id = c.tenant_id AND s.credential_id = c.id
                WHERE c.tenant_id = :tenant AND c.credential_kind = 'SHAREPOINT_APP'
                  AND (:global OR c.owner_actor_id = :owner)
                ORDER BY c.created_at, c.id
                """).param("tenant", tenantId.value()).param("global", owner == null)
                .param("owner", owner == null ? null : owner.value())
                .query((r, _) -> new CredentialView(new CredentialId(r.getObject("id", UUID.class)), r.getString("name"),
                        r.getObject("directory_id", UUID.class), r.getObject("client_id", UUID.class),
                        r.getString("cloud"), r.getString("auth_method"), r.getString("connection_status"),
                        r.getString("certificate_thumbprint"),
                        r.getTimestamp("certificate_not_after") == null ? null : r.getTimestamp("certificate_not_after").toInstant(),
                        r.getString("tenant_host"), r.getLong("credential_revision"),
                        r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant(),
                        r.getLong("source_count"), List.of()))
                .list();
    }

    public Optional<Stored> lock(TenantId tenantId, CredentialId credentialId) {
        if (!sources.lockActiveTenant(tenantId)) return Optional.empty();
        // Credential authority precedes every attached Source lock.
        jdbc.sql("SELECT id FROM credentials WHERE tenant_id = :tenant AND id = :credential FOR UPDATE")
                .param("tenant", tenantId.value()).param("credential", credentialId.value()).query(UUID.class).optional();
        return read(tenantId, credentialId, true);
    }

    public Stored readUsable(TenantId tenantId, CredentialId credentialId) {
        var row = read(tenantId, credentialId, false).orElseThrow(SourceException::notFound);
        if (!row.usable()) throw SharePointException.needsUpdate();
        return row;
    }

    private Optional<Stored> read(TenantId tenantId, CredentialId credentialId, boolean lock) {
        return jdbc.sql(SELECT + (lock ? " FOR UPDATE OF sharepoint" : ""))
                .param("tenantId", tenantId.value()).param("credentialId", credentialId.value())
                .query((r, _) -> new Stored(r.getObject("credential_id", UUID.class), r.getString("name"),
                        r.getObject("directory_id", UUID.class), r.getObject("client_id", UUID.class),
                        SharePointProvider.Cloud.valueOf(r.getString("cloud")),
                        SharePointProvider.AuthMethod.valueOf(r.getString("auth_method")),
                        r.getString("connection_status"), r.getLong("credential_revision"), r.getLong("payload_revision"),
                        r.getBytes("secret_ciphertext"), r.getBytes("secret_nonce"), r.getString("secret_key_version"),
                        r.getBytes("private_key_ciphertext"), r.getBytes("private_key_nonce"), r.getString("private_key_version"),
                        r.getBytes("certificate_der"), r.getString("certificate_thumbprint"),
                        r.getTimestamp("certificate_not_after") == null ? null : r.getTimestamp("certificate_not_after").toInstant(),
                        r.getString("tenant_host"), r.getObject("owner_actor_id", UUID.class),
                        "ACTIVE".equals(r.getString("connection_status")) && "ACTIVE".equals(r.getString("credential_status"))
                                && "ACTIVE".equals(r.getString("tenant_status"))))
                .optional();
    }

    public void rename(TenantId tenantId, CredentialId credentialId, long expectedRevision, String name) {
        var row = lock(tenantId, credentialId).orElseThrow(SourceException::notFound);
        requireRevision(row, expectedRevision);
        jdbc.sql("""
                UPDATE credentials SET name = :name, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :credential
                """).param("name", name).param("tenant", tenantId.value())
                .param("credential", credentialId.value()).update();
    }

    public long replaceAuthentication(TenantId tenantId, CredentialId credentialId, long expectedRevision,
            String name, SharePointAuthentication authentication, @Nullable String tenantHost) {
        var row = lock(tenantId, credentialId).orElseThrow(SourceException::notFound);
        requireRevision(row, expectedRevision);
        var envelope = encrypt(tenantId, row.credentialId(), authentication);
        jdbc.sql("""
                UPDATE sharepoint_credentials SET auth_method = :method, connection_status = 'ACTIVE',
                    secret_ciphertext = :secretCiphertext, secret_nonce = :secretNonce, secret_key_version = :secretVersion,
                    private_key_ciphertext = :keyCiphertext, private_key_nonce = :keyNonce, private_key_version = :keyVersion,
                    certificate_der = :certificate, certificate_thumbprint = :thumbprint, certificate_not_after = :notAfter,
                    tenant_host = :host, credential_revision = credential_revision + 1,
                    payload_revision = payload_revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND credential_id = :credential
                """).param("method", authentication.authMethod().name())
                .param("secretCiphertext", envelope.secretCiphertext()).param("secretNonce", envelope.secretNonce())
                .param("secretVersion", envelope.secretKeyVersion()).param("keyCiphertext", envelope.keyCiphertext())
                .param("keyNonce", envelope.keyNonce()).param("keyVersion", envelope.keyKeyVersion())
                .param("certificate", envelope.certificate()).param("thumbprint", envelope.thumbprint())
                .param("notAfter", envelope.notAfter()).param("host", tenantHost)
                .param("tenant", tenantId.value()).param("credential", row.credentialId()).update();
        jdbc.sql("""
                UPDATE credentials SET name = :name, status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :credential
                """).param("name", name).param("tenant", tenantId.value())
                .param("credential", credentialId.value()).update();
        return expectedRevision + 1;
    }

    public void delete(TenantId tenantId, CredentialId credentialId, long expectedRevision) {
        var row = lock(tenantId, credentialId).orElseThrow(SourceException::notFound);
        requireRevision(row, expectedRevision);
        if (!attachedSources(tenantId, credentialId).isEmpty()) {
            throw SourceException.conflict("SharePoint credential is still used by Sources");
        }
        jdbc.sql("DELETE FROM credentials WHERE tenant_id = :tenant AND id = :credential")
                .param("tenant", tenantId.value()).param("credential", credentialId.value()).update();
    }

    public List<SourceId> attachedSources(TenantId tenantId, CredentialId credentialId) {
        return jdbc.sql("""
                SELECT id FROM connector_credential_pairs
                WHERE tenant_id = :tenant AND credential_id = :credential ORDER BY id
                """).param("tenant", tenantId.value()).param("credential", credentialId.value())
                .query((r, _) -> new SourceId(r.getObject("id", UUID.class))).list();
    }

    /** Records what a successful credential test learned; a stale revision simply loses the race. */
    public void recordTenantHost(TenantId tenantId, CredentialId credentialId, long expectedRevision, @Nullable String tenantHost) {
        jdbc.sql("""
                UPDATE sharepoint_credentials SET tenant_host = :host, connection_status = 'ACTIVE',
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND credential_id = :credential AND credential_revision = :revision
                """).param("host", tenantHost).param("tenant", tenantId.value())
                .param("credential", credentialId.value()).param("revision", expectedRevision).update();
    }

    public boolean markNeedsUpdate(TenantId tenantId, CredentialId credentialId, long expectedRevision) {
        var row = lock(tenantId, credentialId).orElse(null);
        if (row == null || !row.usable() || row.revision() != expectedRevision) return false;
        jdbc.sql("""
                UPDATE sharepoint_credentials SET connection_status = 'NEEDS_UPDATE',
                    credential_revision = credential_revision + 1, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND credential_id = :credential AND credential_revision = :revision
                """).param("tenant", tenantId.value()).param("credential", credentialId.value())
                .param("revision", expectedRevision).update();
        jdbc.sql("""
                UPDATE credentials SET status = 'NEEDS_UPDATE', updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :credential
                """).param("tenant", tenantId.value()).param("credential", credentialId.value()).update();
        return true;
    }

    /** Decrypts the stored payload. The caller closes the result. */
    public SharePointAuthentication authentication(TenantId tenantId, Stored row) {
        if (row.authMethod() == SharePointProvider.AuthMethod.CLIENT_SECRET) {
            return SharePointAuthentication.clientSecret(decrypt(tenantId, row.credentialId(), CLIENT_SECRET,
                    row.secretCiphertext(), row.secretNonce(), row.secretKeyVersion()));
        }
        byte[] key = decrypt(tenantId, row.credentialId(), PRIVATE_KEY,
                row.keyCiphertext(), row.keyNonce(), row.keyKeyVersion());
        try {
            return SharePointAuthentication.certificate(new SharePointCertificate(key,
                    requirePayload(row.certificate()), requirePayload(row.thumbprint()), requirePayload(row.notAfter())));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private byte[] decrypt(TenantId tenantId, UUID credentialId, String purpose,
            byte @Nullable [] ciphertext, byte @Nullable [] nonce, @Nullable String keyVersion) {
        if (ciphertext == null || nonce == null || keyVersion == null) throw SharePointException.needsUpdate();
        try {
            return encryption.cipher().decrypt(tenantId, credentialId, purpose,
                    new CredentialCipher.EncryptedCredential(ciphertext, nonce, keyVersion));
        } catch (IllegalStateException exception) {
            throw SharePointException.notConfigured();
        }
    }

    private Envelope encrypt(TenantId tenantId, UUID credentialId, SharePointAuthentication authentication) {
        if (authentication.authMethod() == SharePointProvider.AuthMethod.CLIENT_SECRET) {
            byte[] secret = requirePayload(authentication.clientSecret());
            CredentialCipher.EncryptedCredential encrypted;
            try { encrypted = encryption.cipher().encrypt(tenantId, credentialId, CLIENT_SECRET, secret); }
            finally { Arrays.fill(secret, (byte) 0); }
            return new Envelope(encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(),
                    null, null, null, null, null, null);
        }
        var certificate = requirePayload(authentication.certificate());
        byte[] key = certificate.privateKey();
        CredentialCipher.EncryptedCredential encrypted;
        try { encrypted = encryption.cipher().encrypt(tenantId, credentialId, PRIVATE_KEY, key); }
        finally { Arrays.fill(key, (byte) 0); }
        return new Envelope(null, null, null, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(),
                certificate.certificate(), certificate.thumbprint(), certificate.notAfter());
    }

    private static <T> T requirePayload(@Nullable T value) {
        if (value == null) throw SharePointException.needsUpdate();
        return value;
    }

    private static void requireRevision(Stored row, long expectedRevision) {
        if (expectedRevision < 1 || row.revision() != expectedRevision) {
            throw SourceException.conflict("SharePoint credential revision is stale");
        }
    }

    private record Envelope(byte @Nullable [] secretCiphertext, byte @Nullable [] secretNonce, @Nullable String secretKeyVersion,
            byte @Nullable [] keyCiphertext, byte @Nullable [] keyNonce, @Nullable String keyKeyVersion,
            byte @Nullable [] certificate, @Nullable String thumbprint, @Nullable Instant notAfter) {
        @Override public String toString() { return "SharePointEnvelope[redacted]"; }
    }

    public record Stored(UUID credentialId, String name, UUID directoryId, UUID clientId,
            SharePointProvider.Cloud cloud, SharePointProvider.AuthMethod authMethod, String status,
            long revision, long payloadRevision,
            byte @Nullable [] secretCiphertext, byte @Nullable [] secretNonce, @Nullable String secretKeyVersion,
            byte @Nullable [] keyCiphertext, byte @Nullable [] keyNonce, @Nullable String keyKeyVersion,
            byte @Nullable [] certificate, @Nullable String thumbprint, @Nullable Instant notAfter,
            @Nullable String tenantHost, @Nullable UUID ownerActorId, boolean usable) {
        @Override public String toString() { return "StoredSharePointCredential[redacted]"; }
    }
}
