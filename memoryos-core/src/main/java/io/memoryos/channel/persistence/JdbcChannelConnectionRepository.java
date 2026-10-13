package io.memoryos.channel.persistence;

import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelRegion;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.CredentialCipher;
import io.memoryos.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The Tenant's bots (MEM-246). The secret envelope is written here and never read back into a view. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChannelConnectionRepository {
    private static final String COLUMNS = "id, provider, name, region, app_id, notice_language, enabled, service_account_id, "
            + "revision, created_at, updated_at";
    private final JdbcClient jdbc;

    public JdbcChannelConnectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ChannelConnection> list(TenantId tenant) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM channel_connections WHERE tenant_id = :tenant ORDER BY created_at, id")
                .param("tenant", tenant.value()).query(JdbcChannelConnectionRepository::connection).list();
    }

    public Optional<ChannelConnection> find(TenantId tenant, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM channel_connections WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value()).param("id", id).query(JdbcChannelConnectionRepository::connection).optional();
    }

    /** Whether any bot, of any Tenant, already holds the app. */
    public boolean appInUse(ChannelProvider provider, ChannelRegion region, String appId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM channel_connections
                                       WHERE provider = :provider AND region = :region AND app_id = :app)
                        """)
                .param("provider", provider.name()).param("region", region.name()).param("app", appId)
                .query(Boolean.class).single();
    }

    public ChannelConnection insert(TenantId tenant, UUID id, ChannelProvider provider, String name, ChannelRegion region,
                                    String appId, CredentialCipher.EncryptedCredential secret, String noticeLanguage,
                                    ActorId serviceAccount) {
        return jdbc.sql("""
                        INSERT INTO channel_connections(tenant_id, id, provider, name, region, app_id, secret_ciphertext,
                                                        secret_nonce, secret_key_version, notice_language, enabled,
                                                        service_account_id)
                        VALUES (:tenant, :id, :provider, :name, :region, :app, :ciphertext, :nonce, :keyVersion, :language,
                                FALSE, :account)
                        RETURNING\s""" + COLUMNS)
                .param("tenant", tenant.value()).param("id", id).param("provider", provider.name()).param("name", name)
                .param("region", region.name()).param("app", appId).param("ciphertext", secret.ciphertext())
                .param("nonce", secret.nonce()).param("keyVersion", secret.keyVersion()).param("language", noticeLanguage)
                .param("account", serviceAccount.value())
                .query(JdbcChannelConnectionRepository::connection).single();
    }

    /** Empty when the bot is gone or its revision moved on. */
    public Optional<ChannelConnection> replaceSecret(TenantId tenant, UUID id, long revision,
                                                     CredentialCipher.EncryptedCredential secret) {
        return jdbc.sql("""
                        UPDATE channel_connections SET secret_ciphertext = :ciphertext, secret_nonce = :nonce,
                               secret_key_version = :keyVersion, revision = revision + 1, updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND revision = :revision
                        RETURNING\s""" + COLUMNS)
                .param("ciphertext", secret.ciphertext()).param("nonce", secret.nonce())
                .param("keyVersion", secret.keyVersion()).param("tenant", tenant.value()).param("id", id)
                .param("revision", revision)
                .query(JdbcChannelConnectionRepository::connection).optional();
    }

    /** Empty when the bot is gone or its revision moved on. */
    public Optional<ChannelConnection> setEnabled(TenantId tenant, UUID id, long revision, boolean enabled) {
        return jdbc.sql("""
                        UPDATE channel_connections SET enabled = :enabled, revision = revision + 1, updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND revision = :revision
                        RETURNING\s""" + COLUMNS)
                .param("enabled", enabled).param("tenant", tenant.value()).param("id", id).param("revision", revision)
                .query(JdbcChannelConnectionRepository::connection).optional();
    }

    /** False when the bot is gone or its revision moved on. */
    public boolean delete(TenantId tenant, UUID id, long revision) {
        return jdbc.sql("DELETE FROM channel_connections WHERE tenant_id = :tenant AND id = :id AND revision = :revision")
                .param("tenant", tenant.value()).param("id", id).param("revision", revision).update() == 1;
    }

    private static ChannelConnection connection(ResultSet row, int ignored) throws SQLException {
        return new ChannelConnection(row.getObject("id", UUID.class), ChannelProvider.valueOf(row.getString("provider")),
                row.getString("name"), ChannelRegion.valueOf(row.getString("region")), row.getString("app_id"),
                row.getString("notice_language"), row.getBoolean("enabled"),
                new ActorId(row.getObject("service_account_id", UUID.class)), row.getLong("revision"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
}
