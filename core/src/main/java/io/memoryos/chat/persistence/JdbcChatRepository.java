package io.memoryos.chat.persistence;

import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatMessage.Role;
import io.memoryos.chat.ChatMessage.Status;
import io.memoryos.chat.ChatSession;
import io.memoryos.chat.ChatBranch;
import io.memoryos.chat.ChatCommand;
import io.memoryos.chat.ChatTurnOptions;
import io.memoryos.chat.ChatSource;
import tools.jackson.databind.ObjectMapper;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.TenantId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatRepository {
    private final JdbcClient jdbc;
    private static final ObjectMapper JSON = new ObjectMapper();

    public JdbcChatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID provisionPersona(TenantId tenant, String name, String instructions, String model) {
        jdbc.sql("""
                        INSERT INTO persona(id, tenant_id, builtin_key, name, instructions, model)
                        VALUES (:id, :tenant, 'default', :name, :instructions, :model)
                        ON CONFLICT (tenant_id, builtin_key) DO NOTHING
                        """).param("id", UUID.randomUUID()).param("tenant", tenant.value())
                .param("name", name).param("instructions", instructions).param("model", model)
                .update();
        return jdbc.sql("SELECT id FROM persona WHERE tenant_id=:tenant AND builtin_key='default'")
                .param("tenant", tenant.value()).query(UUID.class).single();
    }

    public ChatSession create(TenantId tenant, ActorId actor, UUID personaId, String title) {
        UUID id = UUID.randomUUID();
        UUID root = UUID.randomUUID();
        var session = jdbc.sql("""
                        INSERT INTO chat_session(id, tenant_id, owner_actor_id, persona_id, root_message_id, title)
                        VALUES (:id, :tenant, :actor, :persona, :root, :title) RETURNING *
                        """).param("id", id).param("tenant", tenant.value()).param("actor", actor.value())
                .param("persona", personaId).param("root", root).param("title", title)
                .query(JdbcChatRepository::session).single();
        jdbc.sql("""
                INSERT INTO chat_message(id, session_id, role, status, finished_at)
                VALUES (:root, :session, 'ROOT', 'COMPLETED', CURRENT_TIMESTAMP)
                """).param("root", root).param("session", id).update();
        return session;
    }

    public Optional<ChatSession> findOwned(TenantId tenant, ActorId actor, UUID id, boolean lock) {
        return jdbc.sql("""
                        SELECT * FROM chat_session
                        WHERE tenant_id = :tenant AND owner_actor_id = :actor AND id = :id AND deleted_at IS NULL
                        """ + (lock ? " FOR UPDATE" : ""))
                .param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .query(JdbcChatRepository::session).optional();
    }

    public List<ChatSession> list(TenantId tenant, ActorId actor, int offset, int limit) {
        return jdbc.sql("""
                        SELECT * FROM chat_session WHERE tenant_id = :tenant AND owner_actor_id = :actor AND deleted_at IS NULL
                        ORDER BY updated_at DESC, id LIMIT :limit OFFSET :offset
                        """).param("tenant", tenant.value()).param("actor", actor.value())
                .param("limit", limit).param("offset", offset).query(JdbcChatRepository::session).list();
    }

    public Optional<ChatMessage> message(UUID session, UUID id) {
        return jdbc.sql("SELECT * FROM chat_message WHERE session_id = :session AND id = :id")
                .param("session", session).param("id", id).query(JdbcChatRepository::message).optional();
    }

    public boolean onSelectedBranch(ChatSession session, UUID id) {
        return jdbc.sql("""
                        WITH RECURSIVE ancestors AS (
                            SELECT id, parent_message_id, 0 AS depth FROM chat_message WHERE session_id = :session AND id = :id
                            UNION ALL
                            SELECT p.id, p.parent_message_id, a.depth + 1
                            FROM ancestors a JOIN chat_message p ON p.id = a.parent_message_id
                            WHERE p.session_id = :session AND p.latest_child_message_id = a.id AND a.depth < 10000
                        ) SELECT EXISTS(SELECT 1 FROM ancestors WHERE id = :root)
                        """).param("session", session.id()).param("id", id).param("root", session.rootMessageId())
                .query(Boolean.class).single();
    }

    public List<ChatMessage> history(ChatSession session, @Nullable UUID after, int limit) {
        UUID start = after == null ? session.rootMessageId() : after;
        if (!onSelectedBranch(session, start)) throw ChatException.invalid("Invalid history cursor.");
        return jdbc.sql("""
                        WITH RECURSIVE branch AS (
                            SELECT m.*, 0 AS depth FROM chat_message m WHERE session_id = :session AND id = :start
                            UNION ALL
                            SELECT m.*, b.depth + 1 FROM branch b
                            JOIN chat_message m ON m.id = b.latest_child_message_id AND m.session_id = :session
                            WHERE b.depth < :limit
                        ) SELECT * FROM branch WHERE depth > 0 ORDER BY depth
                        """).param("session", session.id()).param("start", start).param("limit", limit)
                .query(JdbcChatRepository::message).list();
    }

    public Optional<ReservedRequest> previousRequest(UUID session, UUID requestId) {
        return jdbc.sql("""
                        SELECT * FROM chat_command WHERE session_id = :session AND request_id = :request
                        """).param("session", session).param("request", requestId)
                .query((row, ignored) -> new ReservedRequest(row.getObject("user_message_id", UUID.class),
                        row.getObject("target_message_id", UUID.class), row.getString("request_text"),
                        row.getObject("assistant_message_id", UUID.class), row.getObject("requested_model_id", UUID.class),
                        row.getObject("selected_model_id", UUID.class), row.getString("fallback_reason"),
                        ChatCommand.Operation.valueOf(row.getString("operation")))).optional();
    }

    public record ReservedRequest(UUID userMessageId, UUID parentMessageId, String content, UUID assistantMessageId,
                                  @Nullable UUID requestedModelId, @Nullable UUID selectedModelId, @Nullable String fallbackReason,
                                  ChatCommand.Operation operation) {
    }

    public void saveCommand(UUID session, ChatCommand command, UUID user, UUID assistant,
                            @Nullable UUID selectedModel, @Nullable String fallback) {
        jdbc.sql("""
                INSERT INTO chat_command(session_id, request_id, operation, target_message_id, request_text,
                    requested_model_id, user_message_id, assistant_message_id, selected_model_id, fallback_reason)
                VALUES (:session, :request, :operation, :target, :text, :requested, :user, :assistant, :selected, :fallback)
                """).param("session", session).param("request", command.requestId()).param("operation", command.operation().name())
                .param("target", command.targetMessageId()).param("text", command.text())
                .param("requested", command.modelConfigurationId(), Types.OTHER).param("user", user).param("assistant", assistant)
                .param("selected", selectedModel, Types.OTHER).param("fallback", fallback, Types.VARCHAR).update();
    }

    public void saveModelSelection(UUID session, UUID user, UUID assistant, @Nullable UUID requested, UUID selected, @Nullable String fallback) {
        jdbc.sql("""
                UPDATE chat_message SET requested_model_configuration_id=:requested,
                    selected_model_configuration_id=:selected, model_selection_fallback=:fallback
                WHERE session_id=:session AND id IN (:user, :assistant)
                """).param("session", session).param("user", user).param("assistant", assistant)
                .param("requested", requested, Types.OTHER).param("selected", selected).param("fallback", fallback, Types.VARCHAR).update();
    }

    public boolean hasActiveReply(UUID session) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM chat_message WHERE session_id = :session AND status = 'RUNNING')")
                .param("session", session).query(Boolean.class).single();
    }

    public long messageCount(UUID session) {
        return jdbc.sql("SELECT COUNT(*) FROM chat_message WHERE session_id = :session")
                .param("session", session).query(Long.class).single();
    }

    public void insertPair(UUID session, UUID parent, UUID request, UUID user, UUID assistant,
                           String text, Duration timeout) {
        jdbc.sql("""
                        INSERT INTO chat_message(id, session_id, parent_message_id, role, content, status,
                            client_request_id, original_assistant_message_id, finished_at)
                        VALUES (:id, :session, :parent, 'USER', :text, 'COMPLETED', :request, :assistant, CURRENT_TIMESTAMP)
                        """).param("id", user).param("session", session).param("parent", parent)
                .param("text", text).param("request", request).param("assistant", assistant).update();
        insertAssistant(session, user, assistant, timeout);
        selectChild(session, parent, user);
    }

    public void insertAssistant(UUID session, UUID user, UUID assistant, Duration timeout) {
        jdbc.sql("""
                        INSERT INTO chat_message(id, session_id, parent_message_id, role, status, deadline_at)
                        VALUES (:id, :session, :parent, 'ASSISTANT', 'RUNNING', clock_timestamp() + :timeout * interval '1 millisecond')
                        """).param("id", assistant).param("session", session).param("parent", user)
                .param("timeout", timeout.toMillis()).update();
        selectChild(session, user, assistant);
        touch(session);
    }

    public void selectChild(UUID session, UUID parent, UUID child) {
        jdbc.sql("UPDATE chat_message SET latest_child_message_id = :child WHERE session_id = :session AND id = :parent")
                .param("child", child).param("session", session).param("parent", parent).update();
    }

    private void touch(UUID session) {
        jdbc.sql("UPDATE chat_session SET updated_at = CURRENT_TIMESTAMP WHERE id = :session")
                .param("session", session).update();
    }

    public record Persona(String instructions, String model, ChatTurnOptions options, String revision) {
    }

    /** Serialize an owner's editor/turn mutations before taking session or settings row locks. */
    public void lockOwner(TenantId tenant, ActorId actor) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "chat-owner:" + tenant.value() + ":" + actor.value()).query(rs -> { rs.next(); return true; });
    }

    public boolean usablePersona(TenantId tenant, ActorId actor, UUID id) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM persona WHERE tenant_id=:tenant AND id=:id AND deleted_at IS NULL "
                        + "AND (builtin_key IS NOT NULL OR owner_actor_id=:actor))")
                .param("tenant", tenant.value()).param("id", id).param("actor", actor.value()).query(Boolean.class).single();
    }

    public Persona persona(UUID session) {
        return jdbc.sql("""
                        SELECT p.id, p.model, p.search_enabled, p.context_token_limit, p.output_token_limit,
                            concat_ws(':',p.id,p.revision,p.model_revision,pr.id,pr.revision) AS revision,
                            CASE WHEN p.builtin_key IS NULL THEN concat_ws(chr(10), :base, p.instructions)
                                 WHEN pr.id IS NOT NULL THEN concat_ws(chr(10), p.instructions, pr.instructions)
                                 ELSE p.instructions END AS instructions
                        FROM persona p JOIN chat_session s ON s.persona_id=p.id AND s.tenant_id=p.tenant_id
                        LEFT JOIN chat_project pr ON pr.id=s.project_id AND pr.tenant_id=s.tenant_id AND pr.owner_actor_id=s.owner_actor_id
                        WHERE s.id=:session AND s.deleted_at IS NULL AND p.deleted_at IS NULL
                            AND (p.builtin_key IS NOT NULL OR p.owner_actor_id=s.owner_actor_id)
                        """).param("session", session).param("base", io.memoryos.chat.prompts.ChatPrompts.DEFAULT_SYSTEM)
                .query((row, ignored) -> new Persona(row.getString("instructions"), row.getString("model"),
                        new ChatTurnOptions(row.getBoolean("search_enabled"), personaSources(row.getObject("id", UUID.class)),
                                row.getObject("context_token_limit", Integer.class), row.getObject("output_token_limit", Integer.class)), row.getString("revision")))
                .optional().orElseThrow(ChatException::unavailable);
    }

    private List<UUID> personaSources(UUID persona) {
        return jdbc.sql("SELECT source_id FROM persona_source WHERE persona_id=:persona ORDER BY source_id LIMIT 100")
                .param("persona", persona).query(UUID.class).list();
    }

    public List<ChatBranch> branches(UUID session) {
        return jdbc.sql("SELECT id,parent_message_id,latest_child_message_id FROM chat_message WHERE session_id=:session ORDER BY created_at,id LIMIT 10000")
                .param("session", session).query((row, ignored) -> new ChatBranch(row.getObject("id", UUID.class),
                        row.getObject("parent_message_id", UUID.class), row.getObject("latest_child_message_id", UUID.class))).list();
    }

    public void rename(UUID session, String title) {
        jdbc.sql("UPDATE chat_session SET title=:title,updated_at=CURRENT_TIMESTAMP WHERE id=:session AND deleted_at IS NULL")
                .param("title", title).param("session", session).update();
    }

    public List<UUID> delete(UUID session) {
        jdbc.sql("UPDATE chat_session SET deleted_at=CURRENT_TIMESTAMP WHERE id=:session AND deleted_at IS NULL")
                .param("session", session).update();
        return jdbc.sql("""
                UPDATE chat_message SET status='CANCELED',finished_at=clock_timestamp(),failure_code=NULL
                WHERE session_id=:session AND status='RUNNING' RETURNING id
                """).param("session", session).query(UUID.class).list();
    }

    public void selectPersona(UUID session, UUID persona) {
        jdbc.sql("UPDATE chat_session SET persona_id=:persona,updated_at=CURRENT_TIMESTAMP WHERE id=:session")
                .param("session", session).param("persona", persona).update();
    }

    public void moveProject(UUID session, @Nullable UUID project) {
        jdbc.sql("UPDATE chat_session SET project_id=:project,updated_at=CURRENT_TIMESTAMP WHERE id=:session")
                .param("session", session).param("project", project, Types.OTHER).update();
    }

    public void unlinkProject(TenantId tenant, ActorId actor, UUID project) {
        jdbc.sql("UPDATE chat_session SET project_id=NULL,updated_at=CURRENT_TIMESTAMP WHERE tenant_id=:tenant AND owner_actor_id=:actor AND project_id=:project")
                .param("tenant", tenant.value()).param("actor", actor.value()).param("project", project).update();
    }

    public List<ChatSession> projectSessions(TenantId tenant, ActorId actor, UUID project, int offset, int limit) {
        return jdbc.sql("""
                SELECT * FROM chat_session WHERE tenant_id=:tenant AND owner_actor_id=:actor
                    AND project_id=:project AND deleted_at IS NULL ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("project", project)
                .param("limit", limit).param("offset", offset).query(JdbcChatRepository::session).list();
    }

    public Optional<ChatSession> shared(TenantId tenant, UUID session) {
        return jdbc.sql("""
                SELECT s.* FROM chat_session s JOIN chat_sharing h ON h.session_id=s.id AND h.tenant_id=s.tenant_id
                WHERE s.tenant_id=:tenant AND s.id=:session AND s.deleted_at IS NULL AND h.enabled
                """).param("tenant", tenant.value()).param("session", session).query(JdbcChatRepository::session).optional();
    }

    /**
     * Ancestors only, including the current user; newest first for bounded context selection.
     */
    public List<ChatMessage> context(UUID session, UUID user, int limit) {
        return jdbc.sql("""
                        WITH RECURSIVE history AS (
                            SELECT m.*, 0 AS depth, coalesce(octet_length(content), 0)::bigint AS bytes FROM chat_message m WHERE session_id = :session AND id = :user
                            UNION ALL
                            SELECT m.*, h.depth + 1, h.bytes + coalesce(octet_length(m.content), 0) FROM history h JOIN chat_message m ON m.id = h.parent_message_id
                            WHERE m.session_id = :session AND h.depth < :limit AND h.bytes < 1048576
                        ) SELECT * FROM history WHERE role <> 'ROOT' AND bytes <= 1048576 ORDER BY depth
                        """).param("session", session).param("user", user).param("limit", limit)
                .query(JdbcChatRepository::message).list();
    }

    public record Control(Status status, Instant deadline, @Nullable String failureCode) {
    }

    public Control control(UUID assistant) {
        return jdbc.sql("""
                SELECT status, deadline_at, failure_code FROM chat_message WHERE id = :id AND role = 'ASSISTANT'
                """).param("id", assistant).query((row, ignored) -> new Control(Status.valueOf(row.getString("status")),
                row.getTimestamp("deadline_at").toInstant(), row.getString("failure_code"))).single();
    }

    public boolean finish(UUID session, UUID assistant, Status status, String content,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output, @Nullable Double cost) {
        return finish(session, assistant, status, content, failure, model, input, output, cost, List.of());
    }

    public boolean finish(UUID session, UUID assistant, Status status, String content,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources) {
        // Same lock order as reserve/Stop: session, then message. Reversing it can deadlock terminal races.
        if (jdbc.sql("SELECT id FROM chat_session WHERE id = :session FOR UPDATE").param("session", session)
                .query(UUID.class).optional().isEmpty()) return false;
        int changed = jdbc.sql("""
                        UPDATE chat_message SET
                            status = CASE WHEN deadline_at <= clock_timestamp() THEN 'FAILED' ELSE :status END,
                            failure_code = CASE WHEN deadline_at <= clock_timestamp() THEN 'CHAT_DEADLINE' ELSE :failure END,
                            content = :content, model_name = :model, input_tokens = :input, output_tokens = :output,
                            cost_usd = :cost, sources = CAST(:sources AS jsonb), finished_at = clock_timestamp()
                        WHERE session_id = :session AND id = :id AND role = 'ASSISTANT' AND status = 'RUNNING'
                        """).param("session", session).param("id", assistant).param("status", status.name())
                .param("content", content).param("failure", failure, Types.VARCHAR)
                .param("model", model, Types.VARCHAR).param("input", input, Types.BIGINT)
                .param("output", output, Types.BIGINT).param("cost", cost, Types.DOUBLE)
                .param("sources", JSON.writeValueAsString(sources)).update();
        if (changed == 1) touch(session);
        return changed == 1;
    }

    public int expireRuns() {
        return jdbc.sql("""
                WITH expired AS (
                    SELECT id FROM chat_message WHERE status = 'RUNNING' AND deadline_at < clock_timestamp() - interval '5 seconds'
                    ORDER BY deadline_at LIMIT 100 FOR UPDATE SKIP LOCKED
                ) UPDATE chat_message m SET status = 'FAILED',
                    failure_code = 'CHAT_INTERRUPTED',
                    finished_at = clock_timestamp()
                FROM expired e WHERE m.id = e.id AND m.status = 'RUNNING'
                """).update();
    }

    private static ChatSession session(ResultSet row, int ignored) throws SQLException {
        return new ChatSession(row.getObject("id", UUID.class), row.getObject("persona_id", UUID.class),
                row.getObject("root_message_id", UUID.class), row.getString("title"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(), row.getObject("project_id", UUID.class));
    }

    private static ChatMessage message(ResultSet row, int ignored) throws SQLException {
        var finished = row.getTimestamp("finished_at");
        return new ChatMessage(row.getObject("id", UUID.class), row.getObject("session_id", UUID.class),
                row.getObject("parent_message_id", UUID.class), row.getObject("latest_child_message_id", UUID.class),
                Role.valueOf(row.getString("role")), row.getString("content"), Status.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(), finished == null ? null : finished.toInstant(),
                List.of(JSON.readValue(row.getString("sources"), ChatSource[].class)));
    }
}
