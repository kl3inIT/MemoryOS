package io.memoryos.connector.lark.persistence;

import io.memoryos.connector.CredentialId;
import io.memoryos.connector.LarkGateway.Collaborator;
import io.memoryos.connector.LarkGateway.User;
import io.memoryos.connector.sync.persistence.WorkLeases;
import io.memoryos.shared.TenantId;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The directory of a Lark credential, in generations: who the chats, departments and spaces named by the snapshots
 * of its SYNC Sources contain, and the email of each person named. Only a completed generation is read.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcLarkDirectoryRepository {
    /** What the snapshots of the credential's SYNC Sources name, by Lark's member type. */
    private static final String REFERENCED = """
            SELECT DISTINCT entry.member->>'type' AS type, entry.member->>'id' AS id
            FROM connector_credential_pairs p
            JOIN lark_acl_snapshots s ON s.tenant_id = p.tenant_id AND s.source_id = p.id AND s.observation_revision > 0
            CROSS JOIN LATERAL jsonb_array_elements(s.members_json) AS entry(member)
            WHERE p.tenant_id = :tenant AND p.credential_id = :credential AND p.access_type = 'SYNC'
                AND p.status <> 'DELETING'
            """;
    private static final String ROOT_KIND = """
            CASE referenced.type WHEN 'openchat' THEN 'CHAT' WHEN 'opendepartmentid' THEN 'DEPARTMENT'
                WHEN 'wikispaceid' THEN 'SPACE' END""";
    private final JdbcClient jdbc;

    public JdbcLarkDirectoryRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public enum Kind { CHAT, DEPARTMENT, SPACE }

    public record Run(TenantId tenantId, CredentialId credentialId, long generation) {}

    /** One thing to list for {@code rootKind:rootId}: a page of its sub-departments, or a page of its members. */
    public record Task(Kind rootKind, String rootId, Kind kind, String groupId, boolean childrenListed,
                       @Nullable String pageToken) {}

    public Optional<Run> running(TenantId tenant, CredentialId credential) {
        return jdbc.sql("""
                SELECT generation FROM lark_directory_runs
                WHERE tenant_id = :tenant AND credential_id = :credential AND status = 'RUNNING'
                """).param("tenant", tenant.value()).param("credential", credential.value())
                .query((r, _) -> new Run(tenant, credential, r.getLong("generation"))).optional();
    }

    /**
     * A new run is due when none runs and either none started within {@code interval}, or a snapshot names a chat,
     * a department, a space or a person the active generation has not asked Lark about and none started within
     * {@code settle}. A credential without a SYNC Source is never due.
     */
    public boolean due(TenantId tenant, CredentialId credential, Duration interval, Duration settle) {
        return jdbc.sql("""
                WITH referenced AS (%1$s), active AS (
                    SELECT active_directory_generation AS generation FROM lark_credentials
                    WHERE tenant_id = :tenant AND credential_id = :credential
                ), latest AS (
                    SELECT MAX(started_at) AS started_at, BOOL_OR(status = 'RUNNING') AS running
                    FROM lark_directory_runs WHERE tenant_id = :tenant AND credential_id = :credential
                )
                SELECT EXISTS (SELECT 1 FROM connector_credential_pairs p WHERE p.tenant_id = :tenant
                        AND p.credential_id = :credential AND p.access_type = 'SYNC' AND p.status <> 'DELETING')
                    AND NOT COALESCE((SELECT running FROM latest), FALSE)
                    AND (COALESCE((SELECT started_at FROM latest) <= statement_timestamp() - :interval * INTERVAL '1 second', TRUE)
                        OR ((SELECT started_at FROM latest) <= statement_timestamp() - :settle * INTERVAL '1 second'
                            AND EXISTS (SELECT 1 FROM referenced WHERE
                                (referenced.type = 'openid' AND NOT EXISTS (SELECT 1 FROM lark_directory_users u
                                    WHERE u.tenant_id = :tenant AND u.credential_id = :credential
                                        AND u.generation = (SELECT generation FROM active) AND u.open_id = referenced.id))
                                OR (referenced.type IN ('openchat', 'opendepartmentid', 'wikispaceid')
                                    AND NOT EXISTS (SELECT 1 FROM lark_directory_work w
                                        WHERE w.tenant_id = :tenant AND w.credential_id = :credential
                                            AND w.generation = (SELECT generation FROM active)
                                            AND w.root_kind = %2$s AND w.root_id = referenced.id)))))
                """.formatted(REFERENCED, ROOT_KIND)).param("tenant", tenant.value())
                .param("credential", credential.value()).param("interval", interval.toSeconds())
                .param("settle", settle.toSeconds()).query(Boolean.class).single();
    }

    /** Starts a generation with one task for every chat, department and space a snapshot names. */
    public Run start(TenantId tenant, CredentialId credential) {
        long generation = jdbc.sql("""
                INSERT INTO lark_directory_runs (tenant_id, credential_id, generation, status)
                SELECT :tenant, :credential, COALESCE(MAX(generation), 0) + 1, 'RUNNING' FROM lark_directory_runs
                WHERE tenant_id = :tenant AND credential_id = :credential
                RETURNING generation
                """).param("tenant", tenant.value()).param("credential", credential.value()).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO lark_directory_work (tenant_id, credential_id, generation, root_kind, root_id, kind,
                    group_id, children_listed)
                SELECT :tenant, :credential, :generation, %2$s, referenced.id, %2$s, referenced.id,
                    referenced.type <> 'opendepartmentid'
                FROM (%1$s) referenced
                WHERE referenced.type IN ('openchat', 'opendepartmentid', 'wikispaceid')
                    AND CHAR_LENGTH(referenced.id) BETWEEN 1 AND 128
                """.formatted(REFERENCED, ROOT_KIND)).param("tenant", tenant.value())
                .param("credential", credential.value()).param("generation", generation).update();
        return new Run(tenant, credential, generation);
    }

    /** Whether {@code run} is still the credential's running generation, so what was read for it may be applied. */
    public boolean unchanged(Run run) {
        return running(run.tenantId(), run.credentialId()).filter(run::equals).isPresent();
    }

    public Optional<Task> nextTask(Run run) {
        return jdbc.sql("""
                SELECT root_kind, root_id, kind, group_id, children_listed, page_token FROM lark_directory_work
                WHERE tenant_id = :tenant AND credential_id = :credential AND generation = :generation AND NOT done
                ORDER BY root_kind, root_id, kind, group_id LIMIT 1
                """).param("tenant", run.tenantId().value()).param("credential", run.credentialId().value())
                .param("generation", run.generation())
                .query((r, _) -> new Task(Kind.valueOf(r.getString("root_kind")), r.getString("root_id"),
                        Kind.valueOf(r.getString("kind")), r.getString("group_id"), r.getBoolean("children_listed"),
                        r.getString("page_token"))).optional();
    }

    /** A page of the departments below {@code task}'s department: each is listed for the same root. */
    public void recordChildren(Run run, Task task, Collection<String> departmentIds, @Nullable String nextPageToken) {
        for (String department : departmentIds) addTask(run, task, Kind.DEPARTMENT, department, true);
        advance(run, task, nextPageToken == null, nextPageToken, false);
    }

    /** A page of the people of {@code task}'s chat or department. */
    public void recordMembers(Run run, Task task, Collection<String> openIds, @Nullable String nextPageToken) {
        for (String openId : openIds) addMember(run, task, openId);
        advance(run, task, true, nextPageToken, nextPageToken == null);
    }

    /** A page of a space's members: people directly, and the chats and departments whose people are then listed. */
    public void recordSpaceMembers(Run run, Task task, Collection<Collaborator> members,
            @Nullable String nextPageToken) {
        for (Collaborator member : members) {
            switch (member.memberType()) {
                case "openid" -> addMember(run, task, member.memberId());
                case "openchat" -> addTask(run, task, Kind.CHAT, member.memberId(), true);
                case "opendepartmentid" -> addTask(run, task, Kind.DEPARTMENT, member.memberId(), false);
                default -> { }
            }
        }
        advance(run, task, true, nextPageToken, nextPageToken == null);
    }

    /** A group Lark no longer has, or does not show the app, has nobody in this generation. */
    public void skip(Run run, Task task) {
        advance(run, task, true, null, true);
    }

    private void addTask(Run run, Task root, Kind kind, String groupId, boolean childrenListed) {
        if (groupId.isEmpty() || groupId.length() > 128) return;
        jdbc.sql("""
                INSERT INTO lark_directory_work (tenant_id, credential_id, generation, root_kind, root_id, kind,
                    group_id, children_listed)
                VALUES (:tenant, :credential, :generation, :rootKind, :rootId, :kind, :group, :listed)
                ON CONFLICT DO NOTHING
                """).param("tenant", run.tenantId().value()).param("credential", run.credentialId().value())
                .param("generation", run.generation()).param("rootKind", root.rootKind().name())
                .param("rootId", root.rootId()).param("kind", kind.name()).param("group", groupId)
                .param("listed", childrenListed).update();
    }

    private void addMember(Run run, Task root, String openId) {
        if (openId.isEmpty() || openId.length() > 128) return;
        jdbc.sql("""
                INSERT INTO lark_directory_members (tenant_id, credential_id, generation, root_kind, root_id, open_id)
                VALUES (:tenant, :credential, :generation, :rootKind, :rootId, :openId)
                ON CONFLICT DO NOTHING
                """).param("tenant", run.tenantId().value()).param("credential", run.credentialId().value())
                .param("generation", run.generation()).param("rootKind", root.rootKind().name())
                .param("rootId", root.rootId()).param("openId", openId).update();
    }

    private void advance(Run run, Task task, boolean childrenListed, @Nullable String pageToken, boolean done) {
        jdbc.sql("""
                UPDATE lark_directory_work SET children_listed = :listed, page_token = :token, done = :done
                WHERE tenant_id = :tenant AND credential_id = :credential AND generation = :generation
                    AND root_kind = :rootKind AND root_id = :rootId AND kind = :kind AND group_id = :group
                """).param("listed", childrenListed).param("token", pageToken).param("done", done)
                .param("tenant", run.tenantId().value()).param("credential", run.credentialId().value())
                .param("generation", run.generation()).param("rootKind", task.rootKind().name())
                .param("rootId", task.rootId()).param("kind", task.kind().name()).param("group", task.groupId())
                .update();
    }

    /** People the run has not asked Lark about yet: those a snapshot names and those the listed groups contain. */
    public List<String> pendingUsers(Run run, int limit) {
        return jdbc.sql("""
                SELECT named.open_id FROM (
                    SELECT referenced.id AS open_id FROM (%s) referenced
                    WHERE referenced.type = 'openid' AND CHAR_LENGTH(referenced.id) BETWEEN 1 AND 128
                    UNION
                    SELECT m.open_id FROM lark_directory_members m
                    WHERE m.tenant_id = :tenant AND m.credential_id = :credential AND m.generation = :generation
                ) named
                WHERE NOT EXISTS (SELECT 1 FROM lark_directory_users u WHERE u.tenant_id = :tenant
                    AND u.credential_id = :credential AND u.generation = :generation AND u.open_id = named.open_id)
                ORDER BY named.open_id LIMIT :limit
                """.formatted(REFERENCED)).param("tenant", run.tenantId().value())
                .param("credential", run.credentialId().value()).param("generation", run.generation())
                .param("limit", limit).query(String.class).list();
    }

    /**
     * Records every person asked about. One Lark did not return, one who left and one without an email are kept
     * without an email: they match no reader, and the run does not ask again.
     */
    public void recordUsers(Run run, Collection<String> asked, Collection<User> answered) {
        for (String openId : asked) {
            String email = answered.stream().filter(user -> user.openId().equals(openId) && user.active())
                    .map(User::email).filter(Objects::nonNull).map(value -> value.strip().toLowerCase(Locale.ROOT))
                    .filter(value -> !value.isEmpty() && value.length() <= 320).findFirst().orElse(null);
            jdbc.sql("""
                    INSERT INTO lark_directory_users (tenant_id, credential_id, generation, open_id, email)
                    VALUES (:tenant, :credential, :generation, :openId, :email)
                    ON CONFLICT DO NOTHING
                    """).param("tenant", run.tenantId().value()).param("credential", run.credentialId().value())
                    .param("generation", run.generation()).param("openId", openId).param("email", email).update();
        }
    }

    /** Promotes the run to the active generation and drops every older generation. */
    public void complete(Run run) {
        jdbc.sql("""
                UPDATE lark_directory_runs SET status = 'COMPLETED', finished_at = statement_timestamp()
                WHERE tenant_id = :tenant AND credential_id = :credential AND generation = :generation
                """).param("tenant", run.tenantId().value()).param("credential", run.credentialId().value())
                .param("generation", run.generation()).update();
        jdbc.sql("""
                UPDATE lark_credentials SET active_directory_generation = :generation
                WHERE tenant_id = :tenant AND credential_id = :credential
                """).param("tenant", run.tenantId().value()).param("credential", run.credentialId().value())
                .param("generation", run.generation()).update();
        jdbc.sql("""
                DELETE FROM lark_directory_runs
                WHERE tenant_id = :tenant AND credential_id = :credential AND generation <> :generation
                """).param("tenant", run.tenantId().value()).param("credential", run.credentialId().value())
                .param("generation", run.generation()).update();
    }

    /** Ends the run without promoting it: the active generation stays in force and the failed one keeps its code. */
    public void fail(Run run, String errorCode) {
        for (String table : List.of("lark_directory_work", "lark_directory_members", "lark_directory_users"))
            jdbc.sql("DELETE FROM " + table
                            + " WHERE tenant_id = :tenant AND credential_id = :credential AND generation = :generation")
                    .param("tenant", run.tenantId().value()).param("credential", run.credentialId().value())
                    .param("generation", run.generation()).update();
        jdbc.sql("""
                UPDATE lark_directory_runs SET status = 'FAILED', finished_at = statement_timestamp(), error_code = :code
                WHERE tenant_id = :tenant AND credential_id = :credential AND generation = :generation
                """).param("code", WorkLeases.safeErrorCode(errorCode)).param("tenant", run.tenantId().value())
                .param("credential", run.credentialId().value()).param("generation", run.generation()).update();
        jdbc.sql("""
                DELETE FROM lark_directory_runs r WHERE r.tenant_id = :tenant AND r.credential_id = :credential
                    AND r.generation <> :generation AND r.status = 'FAILED'
                """).param("tenant", run.tenantId().value()).param("credential", run.credentialId().value())
                .param("generation", run.generation()).update();
    }
}
