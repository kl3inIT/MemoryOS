package io.memoryos.sandbox.persistence;

import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The sandbox each conversation works in (MEM-270, V179) and the archives of its workspace (V184). */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSandboxRepository {
    /**
     * A conversation's row; {@code runtimeId} and {@code startedAt} are null while no sandbox is recorded, and
     * {@code startingSince} is set while a start is in progress; {@code archiveKey} is the archive a new sandbox
     * starts from.
     */
    public record Workspace(TenantId tenant, UUID conversation, ActorId actor, String runtime, @Nullable String runtimeId,
                            @Nullable Instant startedAt, Instant lastUsedAt, @Nullable Instant startingSince,
                            @Nullable String archiveKey) {}

    /** A recorded sandbox, for the Worker's reconciliation. */
    public record Recorded(TenantId tenant, UUID conversation, String runtimeId, Instant lastUsedAt) {}

    private final JdbcClient jdbc;

    public JdbcSandboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the conversation's row unless it exists; the conversation and its owner must exist. */
    public void ensure(TenantId tenant, UUID conversation, ActorId actor, String runtime) {
        jdbc.sql("""
                        INSERT INTO sandbox_workspaces(tenant_id, conversation_id, actor_id, runtime)
                        VALUES (:tenant, :conversation, :actor, :runtime)
                        ON CONFLICT (tenant_id, conversation_id) DO NOTHING
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation).param("actor", actor.value())
                .param("runtime", runtime)
                .update();
    }

    /** The conversation's row, locked until the transaction ends, so one caller at a time starts its sandbox. */
    public Optional<Workspace> lock(TenantId tenant, UUID conversation) {
        return jdbc.sql("""
                        SELECT tenant_id, conversation_id, actor_id, runtime, runtime_id, started_at, last_used_at,
                               starting_since, archive_key
                        FROM sandbox_workspaces WHERE tenant_id = :tenant AND conversation_id = :conversation
                        FOR UPDATE
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation)
                .query((rs, _) -> {
                    Timestamp started = rs.getTimestamp("started_at");
                    Timestamp starting = rs.getTimestamp("starting_since");
                    return new Workspace(new TenantId(rs.getObject("tenant_id", UUID.class)),
                            rs.getObject("conversation_id", UUID.class), new ActorId(rs.getObject("actor_id", UUID.class)),
                            rs.getString("runtime"), rs.getString("runtime_id"), started == null ? null : started.toInstant(),
                            rs.getTimestamp("last_used_at").toInstant(), starting == null ? null : starting.toInstant(),
                            rs.getString("archive_key"));
                })
                .optional();
    }

    /** Takes a place in the running bound for a start that runs outside this transaction. */
    public void reserve(TenantId tenant, UUID conversation, UUID token, Instant at) {
        jdbc.sql("""
                        UPDATE sandbox_workspaces SET starting_since = :at, starting_token = :token, revision = revision + 1
                        WHERE tenant_id = :tenant AND conversation_id = :conversation
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation).param("token", token)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** Records the sandbox the start {@code token} made; false when that start no longer holds the row. */
    public boolean started(TenantId tenant, UUID conversation, UUID token, String runtime, String runtimeId, Instant at) {
        return jdbc.sql("""
                        UPDATE sandbox_workspaces
                        SET runtime = :runtime, runtime_id = :runtimeId, started_at = :at, last_used_at = :at,
                            starting_since = NULL, starting_token = NULL, revision = revision + 1
                        WHERE tenant_id = :tenant AND conversation_id = :conversation AND starting_token = :token
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation).param("token", token)
                .param("runtime", runtime).param("runtimeId", runtimeId).param("at", Timestamp.from(at))
                .update() == 1;
    }

    /** Gives back the place a failed start {@code token} took. */
    public void released(TenantId tenant, UUID conversation, UUID token) {
        jdbc.sql("""
                        UPDATE sandbox_workspaces SET starting_since = NULL, starting_token = NULL, revision = revision + 1
                        WHERE tenant_id = :tenant AND conversation_id = :conversation AND starting_token = :token
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation).param("token", token)
                .update();
    }

    public void used(TenantId tenant, UUID conversation, Instant at) {
        jdbc.sql("""
                        UPDATE sandbox_workspaces SET last_used_at = :at, revision = revision + 1
                        WHERE tenant_id = :tenant AND conversation_id = :conversation
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation).param("at", Timestamp.from(at))
                .update();
    }

    /** Forgets the recorded sandbox, if it is still {@code runtimeId}. */
    public void cleared(TenantId tenant, UUID conversation, String runtimeId) {
        jdbc.sql("""
                        UPDATE sandbox_workspaces SET runtime_id = NULL, started_at = NULL, revision = revision + 1
                        WHERE tenant_id = :tenant AND conversation_id = :conversation AND runtime_id = :runtimeId
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation).param("runtimeId", runtimeId)
                .update();
    }

    /**
     * Serialises starting sandboxes across conversations until the transaction ends, so the running bound is counted
     * and taken by one caller at a time: the row lock alone lets two conversations both see room and both start one.
     */
    public void lockStarts() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('memoryos.sandbox.start'))").query().singleRow();
    }

    /**
     * How many places of {@code runtime} are taken: sandboxes used since {@code since}, so still alive in it, and
     * starts in progress since {@code startingSince}; a start older than that died with its process.
     */
    public int running(String runtime, Instant since, Instant startingSince) {
        return jdbc.sql("""
                        SELECT count(*) FROM sandbox_workspaces
                        WHERE runtime = :runtime
                          AND ((runtime_id IS NOT NULL AND last_used_at > :since) OR starting_since > :startingSince)
                        """)
                .param("runtime", runtime).param("since", Timestamp.from(since))
                .param("startingSince", Timestamp.from(startingSince))
                .query(Integer.class).single();
    }

    public List<Recorded> recorded(String runtime) {
        return jdbc.sql("""
                        SELECT tenant_id, conversation_id, runtime_id, last_used_at FROM sandbox_workspaces
                        WHERE runtime = :runtime AND runtime_id IS NOT NULL
                        """)
                .param("runtime", runtime)
                .query((rs, _) -> new Recorded(new TenantId(rs.getObject("tenant_id", UUID.class)),
                        rs.getObject("conversation_id", UUID.class), rs.getString("runtime_id"),
                        rs.getTimestamp("last_used_at").toInstant()))
                .list();
    }

    /** The sandbox the conversation's row names, read without a lock: an archive is taken of a sandbox already alive. */
    public Optional<String> runtimeId(TenantId tenant, UUID conversation, String runtime) {
        return jdbc.sql("""
                        SELECT runtime_id FROM sandbox_workspaces
                        WHERE tenant_id = :tenant AND conversation_id = :conversation AND runtime = :runtime
                          AND runtime_id IS NOT NULL
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation).param("runtime", runtime)
                .query(String.class).optional();
    }

    /** Records an archive before its upload, so the Worker removes it unless it becomes the conversation's archive. */
    public void archiveBegun(TenantId tenant, UUID conversation, String key, Instant at) {
        jdbc.sql("""
                        INSERT INTO sandbox_workspace_archives(object_key, tenant_id, conversation_id, created_at)
                        VALUES (:key, :tenant, :conversation, :at)
                        """)
                .param("key", key).param("tenant", tenant.value()).param("conversation", conversation)
                .param("at", Timestamp.from(at))
                .update();
    }

    /** Makes {@code key} the archive a new sandbox of the conversation starts from; false when the row is gone. */
    public boolean archived(TenantId tenant, UUID conversation, String key, Instant at) {
        return jdbc.sql("""
                        UPDATE sandbox_workspaces SET archive_key = :key, archived_at = :at, revision = revision + 1
                        WHERE tenant_id = :tenant AND conversation_id = :conversation
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation).param("key", key)
                .param("at", Timestamp.from(at))
                .update() == 1;
    }

    /**
     * Archives begun before {@code before} that no conversation starts from: replaced by a later one, never finished,
     * or left by a deleted conversation.
     */
    public List<String> unusedArchives(Instant before, int limit) {
        return jdbc.sql("""
                        SELECT a.object_key FROM sandbox_workspace_archives a
                        WHERE a.created_at < :before
                          AND NOT EXISTS (SELECT 1 FROM sandbox_workspaces w WHERE w.archive_key = a.object_key)
                        ORDER BY a.created_at LIMIT :limit
                        """)
                .param("before", Timestamp.from(before)).param("limit", limit)
                .query(String.class).list();
    }

    /** Forgets an archive whose object is deleted, unless it became a conversation's archive meanwhile. */
    public void archiveDeleted(String key) {
        jdbc.sql("""
                        DELETE FROM sandbox_workspace_archives a WHERE a.object_key = :key
                          AND NOT EXISTS (SELECT 1 FROM sandbox_workspaces w WHERE w.archive_key = a.object_key)
                        """)
                .param("key", key)
                .update();
    }
}
