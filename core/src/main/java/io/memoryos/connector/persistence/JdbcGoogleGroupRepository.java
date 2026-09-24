package io.memoryos.connector.persistence;

import io.memoryos.connector.CredentialId;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Google Group membership of a service-account credential, stored as generations. A run lists the groups page by
 * page, then each group's members page by page; only a completed run becomes the credential's active generation.
 * Callers hold the credential row lock for every write.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcGoogleGroupRepository {
    private static final int MAX_ERROR_MESSAGE = 2000;
    private final JdbcClient jdbc;

    public JdbcGoogleGroupRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    /**
     * Where a running generation stands: listing groups from {@code groupsPageToken}, or, once groups are listed,
     * listing the members of {@code groupEmail} from {@code membersPageToken}; no group left means it can complete.
     */
    public record Cursor(TenantId tenantId, CredentialId credentialId, long generation, boolean groupsListed,
            @Nullable String groupsPageToken, @Nullable String groupEmail, @Nullable String membersPageToken) {}

    public Optional<Cursor> running(TenantId tenant, CredentialId credential) {
        return jdbc.sql("""
                SELECT r.generation, r.groups_listed, r.groups_page_token, r.members_page_token,
                    (SELECT g.group_email FROM google_group_sync_groups g
                     WHERE g.tenant_id=r.tenant_id AND g.credential_id=r.credential_id AND g.generation=r.generation
                         AND NOT g.members_listed
                     ORDER BY g.group_email LIMIT 1) AS group_email
                FROM google_group_sync_runs r
                WHERE r.tenant_id=:tenant AND r.credential_id=:credential AND r.status='RUNNING'
                """).param("tenant", tenant.value()).param("credential", credential.value())
                .query((rs, _) -> new Cursor(tenant, credential, rs.getLong("generation"), rs.getBoolean("groups_listed"),
                        rs.getString("groups_page_token"), rs.getBoolean("groups_listed") ? rs.getString("group_email") : null,
                        rs.getString("members_page_token"))).optional();
    }

    /** A new run is due when none runs and none started within {@code interval}. */
    public boolean due(TenantId tenant, CredentialId credential, Duration interval) {
        return jdbc.sql("""
                SELECT NOT EXISTS (SELECT 1 FROM google_group_sync_runs
                    WHERE tenant_id=:tenant AND credential_id=:credential
                        AND (status='RUNNING' OR started_at > statement_timestamp() - :interval * INTERVAL '1 second'))
                """).param("tenant", tenant.value()).param("credential", credential.value())
                .param("interval", interval.toSeconds()).query(Boolean.class).single();
    }

    public Cursor start(TenantId tenant, CredentialId credential) {
        long generation = jdbc.sql("""
                INSERT INTO google_group_sync_runs (tenant_id, credential_id, generation, status)
                SELECT :tenant, :credential, COALESCE(MAX(generation), 0) + 1, 'RUNNING' FROM google_group_sync_runs
                WHERE tenant_id=:tenant AND credential_id=:credential
                RETURNING generation
                """).param("tenant", tenant.value()).param("credential", credential.value()).query(Long.class).single();
        return new Cursor(tenant, credential, generation, false, null, null, null);
    }

    /** Whether {@code cursor} still describes its run, so a page read against it may be applied. */
    public boolean unchanged(Cursor cursor) {
        return running(cursor.tenantId(), cursor.credentialId()).filter(cursor::equals).isPresent();
    }

    public void recordGroups(Cursor cursor, Collection<String> groupEmails, @Nullable String nextPageToken) {
        for (String group : groupEmails) {
            jdbc.sql("""
                    INSERT INTO google_group_sync_groups (tenant_id, credential_id, generation, group_email)
                    VALUES (:tenant, :credential, :generation, :group) ON CONFLICT DO NOTHING
                    """).param("tenant", cursor.tenantId().value()).param("credential", cursor.credentialId().value())
                    .param("generation", cursor.generation()).param("group", group).update();
        }
        run(cursor, "groups_page_token=:token, groups_listed=(CAST(:token AS TEXT) IS NULL)", nextPageToken);
    }

    public void recordMembers(Cursor cursor, Collection<String> memberEmails, boolean wholeDomain,
            @Nullable String nextPageToken) {
        String group = Objects.requireNonNull(cursor.groupEmail(), "cursor has no group");
        for (String member : memberEmails) {
            jdbc.sql("""
                    INSERT INTO google_group_members (tenant_id, credential_id, generation, group_email, member_email)
                    VALUES (:tenant, :credential, :generation, :group, :member) ON CONFLICT DO NOTHING
                    """).param("tenant", cursor.tenantId().value()).param("credential", cursor.credentialId().value())
                    .param("generation", cursor.generation()).param("group", group).param("member", member).update();
        }
        jdbc.sql("""
                UPDATE google_group_sync_groups SET whole_domain = whole_domain OR :wholeDomain,
                    members_listed = (CAST(:token AS TEXT) IS NULL)
                WHERE tenant_id=:tenant AND credential_id=:credential AND generation=:generation AND group_email=:group
                """).param("wholeDomain", wholeDomain).param("token", nextPageToken)
                .param("tenant", cursor.tenantId().value()).param("credential", cursor.credentialId().value())
                .param("generation", cursor.generation()).param("group", group).update();
        run(cursor, "members_page_token=:token", nextPageToken);
    }

    /** Promotes the run to the active generation and drops every older generation. */
    public void complete(Cursor cursor) {
        jdbc.sql("""
                UPDATE google_group_sync_runs SET status='COMPLETED', finished_at=statement_timestamp(),
                    groups_page_token=NULL, members_page_token=NULL
                WHERE tenant_id=:tenant AND credential_id=:credential AND generation=:generation
                """).param("tenant", cursor.tenantId().value()).param("credential", cursor.credentialId().value())
                .param("generation", cursor.generation()).update();
        jdbc.sql("""
                UPDATE google_drive_credentials SET active_group_generation=:generation
                WHERE tenant_id=:tenant AND credential_id=:credential
                """).param("tenant", cursor.tenantId().value()).param("credential", cursor.credentialId().value())
                .param("generation", cursor.generation()).update();
        jdbc.sql("""
                DELETE FROM google_group_sync_runs WHERE tenant_id=:tenant AND credential_id=:credential AND generation<>:generation
                """).param("tenant", cursor.tenantId().value()).param("credential", cursor.credentialId().value())
                .param("generation", cursor.generation()).update();
    }

    /**
     * Ends the run without promoting it. The active generation stays in force; the failed run keeps only its
     * evidence, and older failed runs are dropped.
     */
    public void fail(Cursor cursor, String errorCode, @Nullable String errorMessage) {
        jdbc.sql("""
                DELETE FROM google_group_sync_groups WHERE tenant_id=:tenant AND credential_id=:credential AND generation=:generation
                """).param("tenant", cursor.tenantId().value()).param("credential", cursor.credentialId().value())
                .param("generation", cursor.generation()).update();
        jdbc.sql("""
                UPDATE google_group_sync_runs SET status='FAILED', finished_at=statement_timestamp(), error_code=:code,
                    error_message=:message, groups_page_token=NULL, members_page_token=NULL
                WHERE tenant_id=:tenant AND credential_id=:credential AND generation=:generation
                """).param("code", errorCode).param("message", truncate(errorMessage))
                .param("tenant", cursor.tenantId().value()).param("credential", cursor.credentialId().value())
                .param("generation", cursor.generation()).update();
        jdbc.sql("""
                DELETE FROM google_group_sync_runs r WHERE r.tenant_id=:tenant AND r.credential_id=:credential
                    AND r.generation<>:generation AND r.status='FAILED'
                """).param("tenant", cursor.tenantId().value()).param("credential", cursor.credentialId().value())
                .param("generation", cursor.generation()).update();
    }

    /** Forgets every generation, so the credential's memberships stop granting access at once. */
    public void removeAll(TenantId tenant, CredentialId credential) {
        jdbc.sql("""
                UPDATE google_drive_credentials SET active_group_generation=NULL
                WHERE tenant_id=:tenant AND credential_id=:credential
                """).param("tenant", tenant.value()).param("credential", credential.value()).update();
        jdbc.sql("DELETE FROM google_group_sync_runs WHERE tenant_id=:tenant AND credential_id=:credential")
                .param("tenant", tenant.value()).param("credential", credential.value()).update();
    }

    private void run(Cursor cursor, String assignments, @Nullable String token) {
        jdbc.sql("UPDATE google_group_sync_runs SET " + assignments
                        + " WHERE tenant_id=:tenant AND credential_id=:credential AND generation=:generation")
                .param("token", token).param("tenant", cursor.tenantId().value())
                .param("credential", cursor.credentialId().value()).param("generation", cursor.generation()).update();
    }

    private static @Nullable String truncate(@Nullable String message) {
        return message == null || message.length() <= MAX_ERROR_MESSAGE ? message : message.substring(0, MAX_ERROR_MESSAGE);
    }
}
