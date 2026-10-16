package io.memoryos.connector.lark.persistence;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkCredentialService.CredentialView;
import io.memoryos.connector.LarkException;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.shared.CredentialCipher;
import io.memoryos.connector.source.persistence.JdbcSourceRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcLarkCredentialRepository {
    static final String APP_SECRET = "app-secret";
    private static final String SELECT = """
            SELECT lark.*, credential.name, credential.owner_actor_id, credential.credential_revision,
                   credential.status AS credential_status, tenant.status AS tenant_status
            FROM credentials credential
            JOIN lark_credentials lark
              ON lark.tenant_id = credential.tenant_id AND lark.credential_id = credential.id
            JOIN tenants tenant ON tenant.id = credential.tenant_id
            WHERE credential.tenant_id = :tenantId AND credential.id = :credentialId
              AND credential.credential_kind = 'LARK_APP'
            """;

    private final JdbcClient jdbc;
    private final JdbcSourceRepository sources;
    private final LarkCredentialConfiguration encryption;

    public JdbcLarkCredentialRepository(JdbcClient jdbc, JdbcSourceRepository sources,
            LarkCredentialConfiguration encryption) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.encryption = encryption;
    }

    public void requireConfigured() { encryption.cipher(); }

    public CredentialId create(TenantId tenantId, ActorId owner, String name, LarkGateway.Region region, String appId,
            byte[] appSecret, LarkGateway.Bot bot, Set<String> missingScopes) {
        if (!sources.lockActiveTenant(tenantId)) throw SourceException.notFound();
        UUID credentialId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO credentials (id, tenant_id, name, credential_kind, status, owner_actor_id)
                VALUES (:id, :tenant, :name, 'LARK_APP', 'ACTIVE', :owner)
                """).param("id", credentialId).param("tenant", tenantId.value())
                .param("name", name).param("owner", owner.value()).update();
        var encrypted = encrypt(tenantId, credentialId, appSecret);
        jdbc.sql("""
                INSERT INTO lark_credentials (tenant_id, credential_id, region, app_id, secret_ciphertext,
                    secret_nonce, secret_key_version, bot_name, bot_open_id, missing_scopes)
                VALUES (:tenant, :credential, :region, :app, :ciphertext, :nonce, :version, :botName, :botOpenId,
                    :missing)
                """).param("tenant", tenantId.value()).param("credential", credentialId)
                .param("region", region.name()).param("app", appId)
                .param("ciphertext", encrypted.ciphertext()).param("nonce", encrypted.nonce())
                .param("version", encrypted.keyVersion())
                .param("botName", bot.appName()).param("botOpenId", bot.openId())
                .param("missing", joined(missingScopes)).update();
        return new CredentialId(credentialId);
    }

    public List<CredentialView> list(TenantId tenantId, @Nullable ActorId owner) {
        return jdbc.sql("""
                SELECT c.id, c.name, l.region, l.app_id, c.status, l.bot_name, l.missing_scopes, c.credential_revision,
                  c.created_at, c.updated_at,
                  (SELECT COUNT(*) FROM connector_credential_pairs p
                   WHERE p.tenant_id = c.tenant_id AND p.credential_id = c.id) AS source_count
                FROM credentials c
                JOIN lark_credentials l ON l.tenant_id = c.tenant_id AND l.credential_id = c.id
                WHERE c.tenant_id = :tenant AND c.credential_kind = 'LARK_APP'
                  AND (:global OR c.owner_actor_id = :owner)
                ORDER BY c.created_at, c.id
                """).param("tenant", tenantId.value()).param("global", owner == null)
                .param("owner", owner == null ? null : owner.value())
                .query((r, _) -> new CredentialView(new CredentialId(r.getObject("id", UUID.class)), r.getString("name"),
                        r.getString("region"), r.getString("app_id"), r.getString("status"), r.getString("bot_name"),
                        split(r.getString("missing_scopes")), r.getLong("credential_revision"), r.getTimestamp("created_at").toInstant(),
                        r.getTimestamp("updated_at").toInstant(), r.getLong("source_count"), List.of()))
                .list();
    }

    public Optional<Stored> lock(TenantId tenantId, CredentialId credentialId) {
        if (!sources.lockActiveTenant(tenantId)) return Optional.empty();
        // Credential authority precedes every attached Source lock.
        jdbc.sql("SELECT id FROM credentials WHERE tenant_id = :tenant AND id = :credential FOR UPDATE")
                .param("tenant", tenantId.value()).param("credential", credentialId.value()).query(UUID.class).optional();
        return read(tenantId, credentialId, true);
    }

    /** The credential whatever its status, for showing it; only opening a session needs a usable one. */
    public Stored read(TenantId tenantId, CredentialId credentialId) {
        return read(tenantId, credentialId, false).orElseThrow(SourceException::notFound);
    }

    public Stored readUsable(TenantId tenantId, CredentialId credentialId) {
        var row = read(tenantId, credentialId, false).orElseThrow(SourceException::notFound);
        if (!row.usable()) throw LarkException.needsUpdate();
        return row;
    }

    private Optional<Stored> read(TenantId tenantId, CredentialId credentialId, boolean lock) {
        return jdbc.sql(SELECT + (lock ? " FOR UPDATE OF lark, credential" : ""))
                .param("tenantId", tenantId.value()).param("credentialId", credentialId.value())
                .query((r, _) -> new Stored(r.getObject("credential_id", UUID.class), r.getString("name"),
                        LarkGateway.Region.valueOf(r.getString("region")), r.getString("app_id"),
                        r.getString("credential_status"), r.getLong("credential_revision"),
                        r.getBytes("secret_ciphertext"), r.getBytes("secret_nonce"), r.getString("secret_key_version"),
                        r.getString("bot_name"), r.getString("bot_open_id"), r.getObject("owner_actor_id", UUID.class),
                        "ACTIVE".equals(r.getString("credential_status")) && "ACTIVE".equals(r.getString("tenant_status"))))
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

    public long replaceSecret(TenantId tenantId, CredentialId credentialId, long expectedRevision, String name,
            byte[] appSecret, LarkGateway.Bot bot, Set<String> missingScopes) {
        var row = lock(tenantId, credentialId).orElseThrow(SourceException::notFound);
        requireRevision(row, expectedRevision);
        var encrypted = encrypt(tenantId, row.credentialId(), appSecret);
        jdbc.sql("""
                UPDATE lark_credentials SET secret_ciphertext = :ciphertext, secret_nonce = :nonce,
                    secret_key_version = :version, bot_name = :botName, bot_open_id = :botOpenId,
                    missing_scopes = :missing, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND credential_id = :credential
                """).param("ciphertext", encrypted.ciphertext()).param("nonce", encrypted.nonce())
                .param("version", encrypted.keyVersion()).param("botName", bot.appName())
                .param("botOpenId", bot.openId()).param("missing", joined(missingScopes))
                .param("tenant", tenantId.value())
                .param("credential", row.credentialId()).update();
        jdbc.sql("""
                UPDATE credentials SET name = :name, status = 'ACTIVE',
                    credential_revision = credential_revision + 1, payload_revision = payload_revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :credential
                """).param("name", name).param("tenant", tenantId.value())
                .param("credential", credentialId.value()).update();
        return expectedRevision + 1;
    }

    public void delete(TenantId tenantId, CredentialId credentialId, long expectedRevision) {
        var row = lock(tenantId, credentialId).orElseThrow(SourceException::notFound);
        requireRevision(row, expectedRevision);
        if (!attachedSources(tenantId, credentialId).isEmpty()) {
            throw SourceException.conflict("Lark credential is still used by Sources");
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
    public void recordVerification(TenantId tenantId, CredentialId credentialId, long expectedRevision,
            LarkGateway.Bot bot, Set<String> missingScopes) {
        jdbc.sql("""
                UPDATE lark_credentials lark SET bot_name = :botName, bot_open_id = :botOpenId,
                    missing_scopes = :missing, updated_at = CURRENT_TIMESTAMP
                FROM credentials credential
                WHERE lark.tenant_id = :tenant AND lark.credential_id = :credential
                  AND credential.tenant_id = lark.tenant_id AND credential.id = lark.credential_id
                  AND credential.credential_revision = :revision
                """).param("botName", bot.appName()).param("botOpenId", bot.openId())
                .param("missing", joined(missingScopes))
                .param("tenant", tenantId.value()).param("credential", credentialId.value())
                .param("revision", expectedRevision).update();
    }

    public boolean markNeedsUpdate(TenantId tenantId, CredentialId credentialId, long expectedRevision) {
        var row = lock(tenantId, credentialId).orElse(null);
        if (row == null || !row.usable() || row.revision() != expectedRevision) return false;
        jdbc.sql("""
                UPDATE credentials SET status = 'NEEDS_UPDATE', credential_revision = credential_revision + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = :tenant AND id = :credential AND credential_revision = :revision
                """).param("tenant", tenantId.value()).param("credential", credentialId.value())
                .param("revision", expectedRevision).update();
        return true;
    }

    /** Decrypts the stored App Secret into a gateway credential. The caller closes the result. */
    public LarkGateway.Credential credential(TenantId tenantId, Stored row) {
        byte[] secret;
        try {
            secret = encryption.cipher().decrypt(tenantId, row.credentialId(), APP_SECRET,
                    new CredentialCipher.EncryptedCredential(row.secretCiphertext(), row.secretNonce(), row.secretKeyVersion()));
        } catch (IllegalStateException exception) {
            throw LarkException.notConfigured();
        }
        try {
            return new LarkGateway.Credential(row.region(), row.appId(), secret);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private CredentialCipher.EncryptedCredential encrypt(TenantId tenantId, UUID credentialId, byte[] appSecret) {
        return encryption.cipher().encrypt(tenantId, credentialId, APP_SECRET, appSecret);
    }

    /** Scope names hold no space, so a space separates them; sorted, so the same set is the same text. */
    private static String joined(Set<String> scopes) {
        String text = scopes.stream().sorted().collect(Collectors.joining(" "));
        return text.length() > 1024 ? text.substring(0, text.lastIndexOf(' ', 1024)) : text;
    }

    private static List<String> split(@Nullable String scopes) {
        return scopes == null || scopes.isBlank() ? List.of() : List.of(scopes.split(" "));
    }

    private static void requireRevision(Stored row, long expectedRevision) {
        if (expectedRevision < 1 || row.revision() != expectedRevision) {
            throw SourceException.conflict("Lark credential revision is stale");
        }
    }

    public record Stored(UUID credentialId, String name, LarkGateway.Region region, String appId, String status,
            long revision, byte[] secretCiphertext, byte[] secretNonce, String secretKeyVersion,
            @Nullable String botName, @Nullable String botOpenId, @Nullable UUID ownerActorId, boolean usable) {
        @Override public @NonNull String toString() { return "StoredLarkCredential[redacted]"; }
    }
}
