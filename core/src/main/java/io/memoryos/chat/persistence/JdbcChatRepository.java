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
import io.memoryos.chat.ChatFileDescriptor;
import tools.jackson.databind.ObjectMapper;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        INSERT INTO persona(id, tenant_id, builtin_key, name, instructions, model)
                        VALUES (:id, :tenant, 'default', :name, :instructions, :model)
                        ON CONFLICT (tenant_id, builtin_key) DO NOTHING
                        """).param("id", id).param("tenant", tenant.value())
                .param("name", name).param("instructions", instructions).param("model", model)
                .update();
        // A new default agent starts with every agent tool, matching the migrated default.
        if (inserted > 0) jdbc.sql("""
                        INSERT INTO persona_tool(tenant_id, persona_id, tool_key)
                        SELECT :tenant, :id, tool FROM unnest(CAST(:tools AS text[])) AS tool
                        """).param("tenant", tenant.value()).param("id", id)
                .param("tools", io.memoryos.chat.ChatPersonaService.TOOLS.stream().sorted().toArray(String[]::new)).update();
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

    /** Every conversation the member owns that is not deleted, for Delete All Chats. */
    public List<UUID> ownedIds(TenantId tenant, ActorId actor) {
        return jdbc.sql("SELECT id FROM chat_session WHERE tenant_id = :tenant AND owner_actor_id = :actor AND deleted_at IS NULL")
                .param("tenant", tenant.value()).param("actor", actor.value()).query(UUID.class).list();
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
                        ChatCommand.Operation.valueOf(row.getString("operation")),
                        List.of(JSON.readValue(row.getString("file_ids"), UUID[].class)), io.memoryos.chat.WebSearchMode.valueOf(row.getString("web_search")),
                        row.getBoolean("deep_research"))).optional();
    }

    public record ReservedRequest(UUID userMessageId, UUID parentMessageId, String content, UUID assistantMessageId,
                                  @Nullable UUID requestedModelId, @Nullable UUID selectedModelId, @Nullable String fallbackReason,
                                  ChatCommand.Operation operation, List<UUID> fileIds, io.memoryos.chat.WebSearchMode webSearch,
                                  boolean deepResearch) {
    }

    public void saveCommand(UUID session, ChatCommand command, UUID user, UUID assistant,
                            @Nullable UUID selectedModel, @Nullable String fallback) {
        jdbc.sql("""
                INSERT INTO chat_command(session_id, request_id, operation, target_message_id, request_text,
                    requested_model_id, user_message_id, assistant_message_id, selected_model_id, fallback_reason, file_ids, web_search, deep_research)
                VALUES (:session, :request, :operation, :target, :text, :requested, :user, :assistant, :selected, :fallback, CAST(:files AS jsonb), :web, :research)
                """).param("session", session).param("request", command.requestId()).param("operation", command.operation().name())
                .param("target", command.targetMessageId()).param("text", command.text())
                .param("requested", command.modelConfigurationId(), Types.OTHER).param("user", user).param("assistant", assistant)
                .param("selected", selectedModel, Types.OTHER).param("fallback", fallback, Types.VARCHAR)
                .param("files", JSON.writeValueAsString(command.fileIds())).param("web", command.webSearch().name()).param("research", command.deepResearch()).update();
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
                           String text, Duration timeout, List<ChatFileDescriptor> files) {
        jdbc.sql("""
                        INSERT INTO chat_message(id, session_id, parent_message_id, role, content, status,
                            client_request_id, original_assistant_message_id, finished_at, files)
                        VALUES (:id, :session, :parent, 'USER', :text, 'COMPLETED', :request, :assistant, CURRENT_TIMESTAMP, CAST(:files AS jsonb))
                        """).param("id", user).param("session", session).param("parent", parent)
                .param("text", text).param("request", request).param("assistant", assistant)
                .param("files", JSON.writeValueAsString(files)).update();
        insertAssistant(session, user, assistant, timeout);
        selectChild(session, parent, user);
    }

    /** {@code deadline_at} holds the run lease expiry, renewed while the owning process is alive. */
    public void insertAssistant(UUID session, UUID user, UUID assistant, Duration lease) {
        jdbc.sql("""
                        INSERT INTO chat_message(id, session_id, parent_message_id, role, status, deadline_at)
                        VALUES (:id, :session, :parent, 'ASSISTANT', 'RUNNING', clock_timestamp() + :lease * interval '1 millisecond')
                        """).param("id", assistant).param("session", session).param("parent", user)
                .param("lease", lease.toMillis()).update();
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

    /**
     * One session's agent settings under the session owner's use authority.
     *
     * @param tools          agent tool keys ({@code search}, {@code web_search}, {@code image_generation})
     * @param mcpServerIds   attached MCP servers; null for the builtin agent, which reaches every accessible server
     */
    public record Persona(String instructions, String model, ChatTurnOptions options, String revision,
                          @Nullable UUID modelConfigurationId, List<UUID> fileIds, Set<String> tools,
                          @Nullable List<UUID> mcpServerIds, boolean datetimeAware) {
    }

    /** Serialize an owner's editor/turn mutations before taking session or settings row locks. */
    public void lockOwner(TenantId tenant, ActorId actor) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "chat-owner:" + tenant.value() + ":" + actor.value()).query(rs -> { rs.next(); return true; });
    }

    public boolean usablePersona(TenantId tenant, ActorId actor, UUID id, boolean agentsManage) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM persona p WHERE p.tenant_id=:tenant AND p.id=:id AND p.deleted_at IS NULL AND "
                        + AgentAccessSql.USES + ")")
                .param("tenant", tenant.value()).param("id", id).param("actor", actor.value()).param("agentsManage", agentsManage)
                .query(Boolean.class).single();
    }

    public Persona persona(UUID session, boolean lock, boolean agentsManage) {
        return jdbc.sql("""
                        SELECT p.id, p.builtin_key, p.model, p.model_configuration_id, p.context_token_limit, p.output_token_limit,
                            p.task_prompt, p.datetime_aware, p.knowledge_cutoff,
                            CASE WHEN p.builtin_key IS NULL THEN p.file_ids ELSE COALESCE(pr.file_ids,'[]'::jsonb) END AS file_ids,
                            concat_ws(':',p.id,p.revision,p.model_revision,pr.id,pr.revision) AS revision,
                            CASE WHEN p.builtin_key IS NULL AND p.replace_base_system_prompt AND p.datetime_aware
                                      AND position('{{CURRENT_DATETIME}}' IN p.instructions) = 0
                                     THEN concat_ws(chr(10), p.instructions, 'The current date is {{CURRENT_DATETIME}}.')
                                 WHEN p.builtin_key IS NULL AND p.replace_base_system_prompt THEN p.instructions
                                 WHEN p.builtin_key IS NULL THEN concat_ws(chr(10), :base, p.instructions)
                                 WHEN pr.id IS NOT NULL THEN concat_ws(chr(10), p.instructions, pr.instructions)
                                 ELSE p.instructions END AS instructions
                        FROM chat_session s JOIN persona p ON s.persona_id=p.id AND s.tenant_id=p.tenant_id
                        LEFT JOIN chat_project pr ON pr.id=s.project_id AND pr.tenant_id=s.tenant_id AND pr.owner_actor_id=s.owner_actor_id
                        WHERE s.id=:session AND s.deleted_at IS NULL AND p.deleted_at IS NULL AND
                        """ + AgentAccessSql.USES.replace(":actor", "s.owner_actor_id") + (lock ? " FOR SHARE OF p" : ""))
                .param("session", session).param("base", io.memoryos.chat.prompts.ChatPrompts.DEFAULT_SYSTEM)
                .param("agentsManage", agentsManage)
                .query((row, ignored) -> {
                    UUID id = row.getObject("id", UUID.class);
                    boolean builtin = row.getString("builtin_key") != null;
                    var cutoff = row.getTimestamp("knowledge_cutoff");
                    var tools = personaTools(id);
                    return new Persona(row.getString("instructions"), row.getString("model"),
                            new ChatTurnOptions(tools.contains("search"), personaSources(id),
                                    row.getObject("context_token_limit", Integer.class), row.getObject("output_token_limit", Integer.class),
                                    cutoff == null ? null : cutoff.toInstant(), row.getString("task_prompt"),
                                    tools.contains("code_interpreter")),
                            row.getString("revision"), row.getObject("model_configuration_id", UUID.class),
                            List.of(JSON.readValue(row.getString("file_ids"), UUID[].class)), tools,
                            builtin ? null : personaMcpServers(id), row.getBoolean("datetime_aware"));
                })
                .optional().orElseThrow(ChatException::unavailable);
    }

    private Set<String> personaTools(UUID persona) {
        return Set.copyOf(jdbc.sql("SELECT tool_key FROM persona_tool WHERE persona_id=:persona").param("persona", persona).query(String.class).list());
    }

    private List<UUID> personaMcpServers(UUID persona) {
        return jdbc.sql("SELECT server_id FROM persona_mcp_server WHERE persona_id=:persona ORDER BY server_id")
                .param("persona", persona).query(UUID.class).list();
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
        jdbc.sql("UPDATE chat_session SET title=:title,title_naming_pending=false,updated_at=CURRENT_TIMESTAMP WHERE id=:session AND deleted_at IS NULL")
                .param("title", title).param("session", session).update();
    }

    public boolean claimTitle(UUID session) {
        return jdbc.sql("UPDATE chat_session SET title_naming_pending=false WHERE id=:id AND title_naming_pending=true AND deleted_at IS NULL")
                .param("id", session).update() == 1;
    }

    public void completeTitle(ChatSession expected, String title) {
        jdbc.sql("UPDATE chat_session SET title=:title,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND updated_at=:updated AND title=:previous AND deleted_at IS NULL")
                .param("id", expected.id()).param("updated", java.sql.Timestamp.from(expected.updatedAt()))
                .param("previous", expected.title()).param("title", title).update();
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

    public record Control(Status status, @Nullable String failureCode) {
    }

    public Control control(UUID assistant) {
        return jdbc.sql("""
                SELECT status, failure_code FROM chat_message WHERE id = :id AND role = 'ASSISTANT'
                """).param("id", assistant).query((row, ignored) -> new Control(Status.valueOf(row.getString("status")),
                row.getString("failure_code"))).single();
    }

    /**
     * At most one reply per session is RUNNING, so these row locks never overlap a session-first terminal lock cycle.
     * Returns the renewed IDs; a row that already ended is not renewed.
     */
    public java.util.Set<UUID> renewLeases(java.util.Collection<UUID> assistants, Duration lease) {
        if (assistants.isEmpty()) return java.util.Set.of();
        return java.util.Set.copyOf(jdbc.sql("""
                UPDATE chat_message SET deadline_at = clock_timestamp() + :lease * interval '1 millisecond'
                WHERE id IN (:ids) AND role = 'ASSISTANT' AND status = 'RUNNING' RETURNING id
                """).param("ids", assistants).param("lease", lease.toMillis()).query(UUID.class).list());
    }

    public boolean finish(UUID session, UUID assistant, Status status, String content,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output, @Nullable Double cost) {
        return finish(session, assistant, status, content, failure, model, input, output, cost, List.of());
    }

    public boolean finish(UUID session, UUID assistant, Status status, String content,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources) {
        return finish(session, assistant, status, content, failure, model, input, output, cost, sources, List.of());
    }

    public boolean finish(UUID session, UUID assistant, Status status, String content,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources, List<io.memoryos.chat.ChatArtifact> artifacts) {
        return finish(session, assistant, status, content, failure, model, input, output, cost, sources, artifacts, io.memoryos.chat.ChatActivity.EMPTY);
    }

    public boolean finish(UUID session, UUID assistant, Status status, String content,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources, List<io.memoryos.chat.ChatArtifact> artifacts,
                          io.memoryos.chat.ChatActivity activity) {
        return finish(session, assistant, status, content, failure, model, input, output, cost, sources, artifacts, activity,
                io.memoryos.chat.ChatResearch.EMPTY);
    }

    public boolean finish(UUID session, UUID assistant, Status status, String content,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources, List<io.memoryos.chat.ChatArtifact> artifacts,
                          io.memoryos.chat.ChatActivity activity, io.memoryos.chat.ChatResearch research) {
        // Same lock order as reserve/Stop: session, then message. Reversing it can deadlock terminal races.
        if (jdbc.sql("SELECT id FROM chat_session WHERE id = :session FOR UPDATE").param("session", session)
                .query(UUID.class).optional().isEmpty()) return false;
        // A lapsed but unreconciled lease is not a failure: a live process finishing proves it was alive.
        // Once reconciliation has failed the row, the RUNNING predicate rejects this late write.
        int changed = jdbc.sql("""
                        UPDATE chat_message SET status = :status, failure_code = :failure,
                            content = :content, model_name = :model, input_tokens = :input, output_tokens = :output,
                            cost_usd = :cost, sources = CAST(:sources AS jsonb), artifacts = CAST(:artifacts AS jsonb),
                            activity = CAST(:activity AS jsonb), is_clarification = :clarification, research_plan = :plan,
                            research_agents = CAST(:agents AS jsonb),
                            finished_at = clock_timestamp()
                        WHERE session_id = :session AND id = :id AND role = 'ASSISTANT' AND status = 'RUNNING'
                        """).param("session", session).param("id", assistant).param("status", status.name())
                .param("content", content).param("failure", failure, Types.VARCHAR)
                .param("model", model, Types.VARCHAR).param("input", input, Types.BIGINT)
                .param("output", output, Types.BIGINT).param("cost", cost, Types.DOUBLE)
                .param("sources", JSON.writeValueAsString(sources)).param("artifacts", JSON.writeValueAsString(artifacts))
                .param("activity", JSON.writeValueAsString(activity)).param("clarification", research.clarification())
                .param("plan", research.plan(), Types.VARCHAR).param("agents", JSON.writeValueAsString(research.agents())).update();
        if (changed == 1) touch(session);
        return changed == 1;
    }

    /** One bounded batch of every RUNNING row, regardless of lease; only valid when no process can own one. */
    public int failOrphanedRuns() {
        return jdbc.sql("""
                WITH orphaned AS (
                    SELECT id FROM chat_message WHERE status = 'RUNNING' ORDER BY deadline_at LIMIT 100 FOR UPDATE SKIP LOCKED
                ) UPDATE chat_message m SET status = 'FAILED',
                    failure_code = 'CHAT_INTERRUPTED',
                    finished_at = clock_timestamp()
                FROM orphaned o WHERE m.id = o.id AND m.status = 'RUNNING'
                """).update();
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

    static ChatSession session(ResultSet row, int ignored) throws SQLException {
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
                List.of(JSON.readValue(row.getString("sources"), ChatSource[].class)),
                List.of(JSON.readValue(row.getString("files"), ChatFileDescriptor[].class)),
                List.of(JSON.readValue(row.getString("artifacts"), io.memoryos.chat.ChatArtifact[].class)),
                JSON.readValue(row.getString("activity"), io.memoryos.chat.ChatActivity.class),
                new io.memoryos.chat.ChatResearch(row.getBoolean("is_clarification"), row.getString("research_plan"),
                        List.of(JSON.readValue(row.getString("research_agents"), io.memoryos.chat.ChatResearch.Agent[].class))),
                row.getString("failure_code"));
    }
}
