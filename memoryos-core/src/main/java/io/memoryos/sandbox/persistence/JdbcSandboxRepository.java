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

/** The sandbox each conversation works in (MEM-270, V174). */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSandboxRepository {
    /** A conversation's row; {@code runtimeId} and {@code startedAt} are null while no sandbox is recorded. */
    public record Workspace(TenantId tenant, UUID conversation, ActorId actor, String runtime, @Nullable String runtimeId,
                            @Nullable Instant startedAt, Instant lastUsedAt) {}

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
                        SELECT tenant_id, conversation_id, actor_id, runtime, runtime_id, started_at, last_used_at
                        FROM sandbox_workspaces WHERE tenant_id = :tenant AND conversation_id = :conversation
                        FOR UPDATE
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation)
                .query((rs, _) -> {
                    Timestamp started = rs.getTimestamp("started_at");
                    return new Workspace(new TenantId(rs.getObject("tenant_id", UUID.class)),
                            rs.getObject("conversation_id", UUID.class), new ActorId(rs.getObject("actor_id", UUID.class)),
                            rs.getString("runtime"), rs.getString("runtime_id"), started == null ? null : started.toInstant(),
                            rs.getTimestamp("last_used_at").toInstant());
                })
                .optional();
    }

    public void started(TenantId tenant, UUID conversation, String runtime, String runtimeId, Instant at) {
        jdbc.sql("""
                        UPDATE sandbox_workspaces
                        SET runtime = :runtime, runtime_id = :runtimeId, started_at = :at, last_used_at = :at,
                            revision = revision + 1
                        WHERE tenant_id = :tenant AND conversation_id = :conversation
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation).param("runtime", runtime)
                .param("runtimeId", runtimeId).param("at", Timestamp.from(at))
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

    /** How many sandboxes of {@code runtime} were used since {@code since}, so are still alive in it. */
    public int running(String runtime, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM sandbox_workspaces
                        WHERE runtime = :runtime AND runtime_id IS NOT NULL AND last_used_at > :since
                        """)
                .param("runtime", runtime).param("since", Timestamp.from(since))
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
}
