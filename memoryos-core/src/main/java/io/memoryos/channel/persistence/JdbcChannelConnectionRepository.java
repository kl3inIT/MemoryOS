package io.memoryos.channel.persistence;

import io.memoryos.channel.ChannelConnection;
import io.memoryos.channel.ChannelConnectionState;
import io.memoryos.channel.ChannelProvider;
import io.memoryos.channel.ChannelVerification;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.CredentialCipher;
import io.memoryos.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The Tenant's bots (MEM-246). The secret envelope is written here and never read back into a view. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChannelConnectionRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, String>> SETTINGS = new TypeReference<>() {};
    private static final String COLUMNS = "id, provider, name, app_id, settings::text AS settings, notice_language, enabled, "
            + "service_account_id, revision, created_at, updated_at, organisation_key, bot_name, bot_external_id, missing_scopes";
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

    /** A bot that is switched on, with the Tenant it belongs to: what a received message is answered for. */
    public Optional<Enabled> findEnabled(UUID id) {
        return jdbc.sql("SELECT tenant_id, " + COLUMNS + " FROM channel_connections WHERE id = :id AND enabled")
                .param("id", id)
                .query((row, index) -> new Enabled(new TenantId(row.getObject("tenant_id", UUID.class)), connection(row, index)))
                .optional();
    }

    public record Enabled(TenantId tenant, ChannelConnection connection) {}

    /** Whether a bot, switched on or not, still exists. */
    public boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM channel_connections WHERE id = :id)").param("id", id)
                .query(Boolean.class).single();
    }

    /** Every bot that is switched on, of every Tenant: what the process that receives holds open. */
    public List<Enabled> listEnabled() {
        return jdbc.sql("SELECT tenant_id, " + COLUMNS + " FROM channel_connections WHERE enabled ORDER BY id")
                .query((row, index) -> new Enabled(new TenantId(row.getObject("tenant_id", UUID.class)), connection(row, index)))
                .list();
    }

    /** The stored envelope of a bot's secret; empty when the bot is gone or, linked by a code, has none (MEM-283). */
    public Optional<CredentialCipher.EncryptedCredential> secret(TenantId tenant, UUID id) {
        return jdbc.sql("""
                        SELECT secret_ciphertext, secret_nonce, secret_key_version FROM channel_connections
                        WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("tenant", tenant.value()).param("id", id)
                .query((row, ignored) -> {
                    byte[] ciphertext = row.getBytes("secret_ciphertext");
                    return ciphertext == null ? Optional.<CredentialCipher.EncryptedCredential>empty()
                            : Optional.of(new CredentialCipher.EncryptedCredential(ciphertext,
                                    row.getBytes("secret_nonce"), row.getString("secret_key_version")));
                })
                .optional().flatMap(found -> found);
    }

    /** What the platform said of the bot's app at a check; the scopes are kept separated by spaces. */
    public void recordVerification(TenantId tenant, UUID id, ChannelVerification verification) {
        jdbc.sql("""
                        UPDATE channel_connections SET bot_name = :name, bot_external_id = :external, missing_scopes = :scopes
                        WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("name", verification.botName()).param("external", verification.botExternalId())
                .param("scopes", String.join(" ", verification.missingScopes().stream().sorted().toList()))
                .param("tenant", tenant.value()).param("id", id).update();
    }

    /** What the supervisor last wrote of each of the Tenant's bots. */
    public Map<UUID, ChannelConnectionState> states(TenantId tenant) {
        var states = new HashMap<UUID, ChannelConnectionState>();
        jdbc.sql("""
                        SELECT s.connection_id, s.opened_at, s.last_failure_code, s.last_failure_at, s.last_message_at,
                               s.heartbeat_at
                        FROM channel_connection_state s JOIN channel_connections c ON c.id = s.connection_id
                        WHERE c.tenant_id = :tenant
                        """)
                .param("tenant", tenant.value())
                .query((row, ignored) -> states.put(row.getObject("connection_id", UUID.class), new ChannelConnectionState(
                        instant(row.getTimestamp("opened_at")), row.getString("last_failure_code"),
                        instant(row.getTimestamp("last_failure_at")), instant(row.getTimestamp("last_message_at")),
                        instant(row.getTimestamp("heartbeat_at"))))).list();
        return states;
    }

    private static List<String> scopes(@Nullable String joined) {
        return joined == null || joined.isBlank() ? List.of() : List.of(joined.strip().split(" +"));
    }

    private static @Nullable Instant instant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static @Nullable Timestamp timestamp(@Nullable Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** Whether any bot, of any Tenant, already holds the app. */
    public boolean appInUse(ChannelProvider provider, String appId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM channel_connections WHERE provider = :provider AND app_id = :app)
                        """)
                .param("provider", provider.name()).param("app", appId)
                .query(Boolean.class).single();
    }

    /**
     * Whether a bot of the Tenant already holds the app: for a provider whose app is a claim nothing verified until the
     * bot is linked (MEM-283), where linking decides it across Tenants.
     */
    public boolean appInUse(TenantId tenant, ChannelProvider provider, String appId) {
        return jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM channel_connections
                                       WHERE tenant_id = :tenant AND provider = :provider AND app_id = :app)
                        """)
                .param("tenant", tenant.value()).param("provider", provider.name()).param("app", appId)
                .query(Boolean.class).single();
    }

    public ChannelConnection insert(TenantId tenant, UUID id, ChannelProvider provider, String name, String appId,
                                    Map<String, String> settings, CredentialCipher.@Nullable EncryptedCredential secret,
                                    String noticeLanguage, ActorId serviceAccount) {
        return jdbc.sql("""
                        INSERT INTO channel_connections(tenant_id, id, provider, name, app_id, settings, secret_ciphertext,
                                                        secret_nonce, secret_key_version, notice_language, enabled,
                                                        service_account_id)
                        VALUES (:tenant, :id, :provider, :name, :app, CAST(:settings AS JSONB), :ciphertext, :nonce,
                                :keyVersion, :language, FALSE, :account)
                        RETURNING\s""" + COLUMNS)
                .param("tenant", tenant.value()).param("id", id).param("provider", provider.name()).param("name", name)
                .param("app", appId).param("settings", JSON.writeValueAsString(settings))
                .param("ciphertext", secret == null ? null : secret.ciphertext(), Types.BINARY)
                .param("nonce", secret == null ? null : secret.nonce(), Types.BINARY)
                .param("keyVersion", secret == null ? null : secret.keyVersion(), Types.VARCHAR)
                .param("language", noticeLanguage)
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

    /** Empty when the bot is gone or its revision moved on. */
    public Optional<ChannelConnection> updateSettings(TenantId tenant, UUID id, long revision, Map<String, String> settings) {
        return jdbc.sql("""
                        UPDATE channel_connections SET settings = CAST(:settings AS JSONB), revision = revision + 1,
                               updated_at = now()
                        WHERE tenant_id = :tenant AND id = :id AND revision = :revision
                        RETURNING\s""" + COLUMNS)
                .param("settings", JSON.writeValueAsString(settings)).param("tenant", tenant.value()).param("id", id)
                .param("revision", revision)
                .query(JdbcChannelConnectionRepository::connection).optional();
    }

    /** False when the bot is gone or its revision moved on. */
    public boolean delete(TenantId tenant, UUID id, long revision) {
        return jdbc.sql("DELETE FROM channel_connections WHERE tenant_id = :tenant AND id = :id AND revision = :revision")
                .param("tenant", tenant.value()).param("id", id).param("revision", revision).update() == 1;
    }

    private static ChannelConnection connection(ResultSet row, int ignored) throws SQLException {
        return new ChannelConnection(row.getObject("id", UUID.class), ChannelProvider.valueOf(row.getString("provider")),
                row.getString("name"), row.getString("app_id"), JSON.readValue(row.getString("settings"), SETTINGS),
                row.getString("notice_language"), row.getBoolean("enabled"),
                new ActorId(row.getObject("service_account_id", UUID.class)), row.getLong("revision"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                row.getString("organisation_key"), row.getString("bot_name"), row.getString("bot_external_id"),
                scopes(row.getString("missing_scopes")));
    }
}
