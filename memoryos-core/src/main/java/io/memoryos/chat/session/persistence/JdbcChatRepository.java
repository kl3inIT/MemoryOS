package io.memoryos.chat.session.persistence;

import io.memoryos.chat.ChatContextUse;
import io.memoryos.chat.ChatActivity;
import io.memoryos.chat.ChatArtifact;
import io.memoryos.chat.ChatPersonaService;
import io.memoryos.chat.ChatResearch;
import io.memoryos.chat.WebSearchMode;
import io.memoryos.chat.persona.persistence.AgentAccessSql;
import io.memoryos.chat.persona.persistence.DocumentSetAccessSql;
import io.memoryos.ai.ReasoningEffort;
import io.memoryos.chat.ChatException;
import io.memoryos.chat.ChatMessage;
import io.memoryos.chat.ChatMessage.Role;
import io.memoryos.chat.ChatMessage.Status;
import io.memoryos.chat.ChatSession;
import io.memoryos.chat.ChatSurface;
import io.memoryos.chat.ChatBranch;
import io.memoryos.chat.ChatCommand;
import io.memoryos.chat.ChatTurnOptions;
import io.memoryos.chat.ChatSource;
import io.memoryos.chat.ChatToolTranscript;
import io.memoryos.chat.ChatFileDescriptor;
import io.memoryos.chat.prompts.ChatPrompts;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatRepository {
    private final JdbcClient jdbc;
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Every read of a conversation says whether an answer is being written in it, from the partial unique index on
     * running replies. The trailing space lets a text block follow.
     */
    static final String SELECT_SESSION = "SELECT chat_session.*, EXISTS (SELECT 1 FROM chat_message reply"
            + " WHERE reply.session_id = chat_session.id AND reply.status = 'RUNNING') AS reply_running ";

    /**
     * A conversation nobody asked anything in is not a topic, so no list shows it (MEM-273); it still opens from its
     * address. Reads {@code ix_chat_message_parent} by session.
     */
    static String asked(String session) {
        return " EXISTS (SELECT 1 FROM chat_message question WHERE question.session_id = " + session
                + ".id AND question.role = 'USER') ";
    }

    public JdbcChatRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID provisionPersona(TenantId tenant, String name, String instructions) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        INSERT INTO persona(id, tenant_id, builtin_key, name, instructions, avatar_seed)
                        VALUES (:id, :tenant, 'default', :name, :instructions, 'memoryos')
                        ON CONFLICT (tenant_id, builtin_key) DO NOTHING
                        """).param("id", id).param("tenant", tenant.value())
                .param("name", name).param("instructions", instructions)
                .update();
        // A new default agent starts with every agent tool, matching the migrated default.
        if (inserted > 0) jdbc.sql("""
                        INSERT INTO persona_tool(tenant_id, persona_id, tool_key)
                        SELECT :tenant, :id, tool FROM unnest(CAST(:tools AS text[])) AS tool
                        """).param("tenant", tenant.value()).param("id", id)
                .param("tools", ChatPersonaService.TOOLS.stream().sorted().toArray(String[]::new)).update();
        return defaultPersona(tenant).orElseThrow();
    }

    /** The Tenant's built-in default agent, which Tenant provisioning creates. */
    public Optional<UUID> defaultPersona(TenantId tenant) {
        return jdbc.sql("SELECT id FROM persona WHERE tenant_id=:tenant AND builtin_key='default'")
                .param("tenant", tenant.value()).query(UUID.class).optional();
    }

    /**
     * The assistant a channel's conversation is held with: {@code bound} while it is a live custom assistant of the
     * Tenant, else the built-in default (MEM-273).
     */
    public Optional<UUID> channelPersona(TenantId tenant, @Nullable UUID bound) {
        if (bound != null) {
            var found = jdbc.sql("""
                            SELECT id FROM persona
                            WHERE tenant_id=:tenant AND id=:id AND deleted_at IS NULL AND builtin_key IS NULL
                            """)
                    .param("tenant", tenant.value()).param("id", bound).query(UUID.class).optional();
            if (found.isPresent()) return found;
        }
        return defaultPersona(tenant);
    }

    public ChatSession create(TenantId tenant, ActorId actor, UUID personaId, String title, boolean temporary,
                              ChatSurface surface) {
        UUID id = UUID.randomUUID();
        UUID root = UUID.randomUUID();
        var session = jdbc.sql("""
                        INSERT INTO chat_session(id, tenant_id, owner_actor_id, persona_id, root_message_id, title,
                                                 temporary, surface)
                        VALUES (:id, :tenant, :actor, :persona, :root, :title, :temporary, :surface)
                        RETURNING *, FALSE AS reply_running
                        """).param("id", id).param("tenant", tenant.value()).param("actor", actor.value())
                .param("surface", surface.name())
                .param("persona", personaId).param("root", root).param("title", title).param("temporary", temporary)
                .query(JdbcChatRepository::session).single();
        jdbc.sql("""
                INSERT INTO chat_message(id, session_id, role, status, finished_at)
                VALUES (:root, :session, 'ROOT', 'COMPLETED', CURRENT_TIMESTAMP)
                """).param("root", root).param("session", id).update();
        return session;
    }

    /**
     * A conversation branched from another (MEM-153): it keeps the origin's agent, Project and pinned reasoning
     * effort, and records where it came from so its header can link back. Its root message is its own, because a
     * message belongs to one conversation.
     */
    public ChatSession createBranch(TenantId tenant, ActorId actor, ChatSession origin, UUID branchedFromMessageId,
                                    String title) {
        UUID id = UUID.randomUUID();
        UUID root = UUID.randomUUID();
        var session = jdbc.sql("""
                        INSERT INTO chat_session(id, tenant_id, owner_actor_id, persona_id, root_message_id, title,
                                                 project_id, reasoning_effort, branched_from_session_id,
                                                 branched_from_message_id)
                        VALUES (:id, :tenant, :actor, :persona, :root, :title, :project, :effort, :origin, :message)
                        RETURNING *, FALSE AS reply_running
                        """).param("id", id).param("tenant", tenant.value()).param("actor", actor.value())
                .param("persona", origin.personaId()).param("root", root).param("title", title)
                .param("project", origin.projectId())
                .param("effort", origin.reasoningEffort() == null ? null : origin.reasoningEffort().name())
                .param("origin", origin.id()).param("message", branchedFromMessageId)
                .query(JdbcChatRepository::session).single();
        jdbc.sql("""
                INSERT INTO chat_message(id, session_id, role, status, finished_at)
                VALUES (:root, :session, 'ROOT', 'COMPLETED', CURRENT_TIMESTAMP)
                """).param("root", root).param("session", id).update();
        return session;
    }

    /**
     * One message to copy into a branch. The caller chose every id up front, because a question's row has to name
     * the answer that follows it and a parent has to name its child; the deferred constraints let a row point at a
     * copy this same transaction inserts later.
     *
     * @param requestId a fresh client request id for a question, which is unique per conversation
     * @param replyId   the copy of the answer to that question, which its row must name
     */
    public record MessageCopy(UUID originalId, UUID copyId, UUID parentId, @Nullable UUID childId,
                              @Nullable UUID requestId, @Nullable UUID replyId) {}

    /**
     * Copies the given messages of {@code origin} into {@code branch}, in order, keeping what each message said
     * and everything recorded about how it was produced. A copy is never running: an answer still being written
     * is refused before this, and a copied answer is the text the origin already has.
     */
    public void copyMessages(UUID origin, UUID branch, List<MessageCopy> copies) {
        if (copies.isEmpty()) return;
        // One statement for the whole path; parent links between the copies are checked when it completes.
        int inserted = jdbc.sql("""
                INSERT INTO chat_message(id, session_id, parent_message_id, latest_child_message_id, role, content,
                                         status, client_request_id, original_assistant_message_id, created_at,
                                         finished_at, deadline_at, sources, files, artifacts, activity, model_name,
                                         input_tokens, output_tokens, requested_model_configuration_id,
                                         selected_model_configuration_id, model_selection_fallback,
                                         is_clarification, research_plan, research_agents, failure_code, refusal_reason)
                SELECT c.copy_id, :branch, c.parent_id, c.child_id, m.role, m.content, m.status, c.request_id, c.reply_id,
                       m.created_at, m.finished_at, m.deadline_at, m.sources, m.files, m.artifacts, m.activity,
                       m.model_name,
                       m.input_tokens, m.output_tokens, m.requested_model_configuration_id,
                       m.selected_model_configuration_id, m.model_selection_fallback, m.is_clarification,
                       m.research_plan, m.research_agents, m.failure_code, m.refusal_reason
                FROM unnest(CAST(:originals AS uuid[]), CAST(:copies AS uuid[]), CAST(:parents AS uuid[]),
                            CAST(:children AS uuid[]), CAST(:requests AS uuid[]), CAST(:replies AS uuid[]))
                         WITH ORDINALITY AS c(original_id, copy_id, parent_id, child_id, request_id, reply_id, position)
                JOIN chat_message m ON m.id = c.original_id AND m.session_id = :origin AND m.status <> 'RUNNING'
                ORDER BY c.position
                """).param("branch", branch).param("origin", origin)
                .param("originals", uuids(copies, MessageCopy::originalId))
                .param("copies", uuids(copies, MessageCopy::copyId))
                .param("parents", uuids(copies, MessageCopy::parentId))
                .param("children", uuids(copies, MessageCopy::childId))
                .param("requests", uuids(copies, MessageCopy::requestId))
                .param("replies", uuids(copies, MessageCopy::replyId))
                .update();
        if (inserted != copies.size()) throw ChatException.unavailable();
    }

    /** A column of {@code copies} as a text array PostgreSQL casts to {@code uuid[]}; absent values stay null. */
    private static String[] uuids(List<MessageCopy> copies, Function<MessageCopy, @Nullable UUID> column) {
        return copies.stream().map(column).map(id -> id == null ? null : id.toString()).toArray(String[]::new);
    }

    public Optional<ChatSession> findOwned(TenantId tenant, ActorId actor, UUID id, boolean lock) {
        return jdbc.sql(SELECT_SESSION + """
                        FROM chat_session
                        WHERE tenant_id = :tenant AND owner_actor_id = :actor AND id = :id AND deleted_at IS NULL
                        """ + (lock ? " FOR UPDATE" : ""))
                .param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .query(JdbcChatRepository::session).optional();
    }

    /**
     * The newest web topic of an assistant, with its last question or finished answer ({@code content} is cut to
     * 2000 characters, and null with {@code messageAt} before anyone wrote).
     */
    public record LatestTopic(UUID personaId, UUID sessionId, String title, Instant updatedAt, @Nullable String content,
                              @Nullable Instant messageAt, boolean replyRunning) {}

    /** One row per assistant of {@code personas} the actor has a sidebar topic with, read in one statement. */
    public List<LatestTopic> latestTopics(TenantId tenant, ActorId actor, Collection<UUID> personas) {
        if (personas.isEmpty()) return List.of();
        return jdbc.sql("""
                        SELECT s.persona_id, s.id, s.title, s.updated_at, last.content, last.created_at,
                               EXISTS (SELECT 1 FROM chat_message r WHERE r.session_id = s.id AND r.status = 'RUNNING')
                                   AS reply_running
                        FROM unnest(CAST(:personas AS uuid[])) AS p(id)
                        JOIN LATERAL (
                            SELECT * FROM chat_session c
                            WHERE c.tenant_id = :tenant AND c.owner_actor_id = :actor AND c.persona_id = p.id
                              AND c.deleted_at IS NULL AND c.archived_at IS NULL AND NOT c.temporary AND c.surface = 'WEB'
                              AND""" + asked("c") + """

                            ORDER BY c.updated_at DESC, c.id LIMIT 1
                        ) s ON TRUE
                        LEFT JOIN LATERAL (
                            SELECT left(m.content, 2000) AS content, m.created_at FROM chat_message m
                            WHERE m.session_id = s.id AND m.content <> ''
                              AND (m.role = 'USER' OR m.role = 'ASSISTANT' AND m.status = 'COMPLETED')
                            ORDER BY m.created_at DESC, m.role = 'ASSISTANT' DESC, m.id LIMIT 1
                        ) last ON TRUE
                        """).param("tenant", tenant.value()).param("actor", actor.value())
                .param("personas", personas.stream().map(UUID::toString).toArray(String[]::new))
                .query((row, ignored) -> {
                    var messageAt = row.getTimestamp("created_at");
                    return new LatestTopic(row.getObject("persona_id", UUID.class), row.getObject("id", UUID.class),
                            row.getString("title"), row.getTimestamp("updated_at").toInstant(), row.getString("content"),
                            messageAt == null ? null : messageAt.toInstant(), row.getBoolean("reply_running"));
                }).list();
    }

    /** Every conversation the member owns that is not deleted, for Delete All Chats. */
    public List<UUID> ownedIds(TenantId tenant, ActorId actor) {
        return jdbc.sql("SELECT id FROM chat_session WHERE tenant_id = :tenant AND owner_actor_id = :actor AND deleted_at IS NULL")
                .param("tenant", tenant.value()).param("actor", actor.value()).query(UUID.class).list();
    }

    /**
     * The sidebar's conversations, or the archive; an archived conversation is read by when it was archived. A
     * {@code persona} keeps the topics of one assistant (MEM-273).
     */
    public List<ChatSession> list(TenantId tenant, ActorId actor, boolean archived, @Nullable UUID persona,
                                  int offset, int limit) {
        var sql = jdbc.sql(SELECT_SESSION + """
                        FROM chat_session WHERE tenant_id = :tenant AND owner_actor_id = :actor
                            AND deleted_at IS NULL AND NOT temporary AND surface = 'WEB'
                            AND (CASE WHEN :archived THEN archived_at IS NOT NULL ELSE archived_at IS NULL END)
                            AND""" + asked("chat_session") + (persona == null ? "" : " AND persona_id = :persona") + """

                        ORDER BY (CASE WHEN :archived THEN archived_at ELSE updated_at END) DESC, id
                        LIMIT :limit OFFSET :offset
                        """).param("tenant", tenant.value()).param("actor", actor.value()).param("archived", archived)
                .param("limit", limit).param("offset", offset);
        if (persona != null) sql = sql.param("persona", persona);
        return sql.query(JdbcChatRepository::session).list();
    }

    /**
     * Archives or unarchives one conversation the actor owns; idempotent, because a repeated click asks for the
     * state it is already in. False when there is no such conversation.
     */
    public boolean archive(TenantId tenant, ActorId actor, UUID id, boolean archived) {
        return jdbc.sql("""
                UPDATE chat_session SET archived_at = CASE WHEN :archived THEN COALESCE(archived_at, CURRENT_TIMESTAMP) END
                WHERE tenant_id = :tenant AND owner_actor_id = :actor AND id = :id AND deleted_at IS NULL
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("id", id)
                .param("archived", archived).update() == 1;
    }

    /** Archives the owner's conversations, at most {@code limit} of them; answers how many were archived. */
    public int archiveAll(TenantId tenant, ActorId actor, int limit) {
        return jdbc.sql("""
                UPDATE chat_session SET archived_at = CURRENT_TIMESTAMP
                WHERE (tenant_id, id) IN (
                    SELECT tenant_id, id FROM chat_session
                    WHERE tenant_id = :tenant AND owner_actor_id = :actor AND deleted_at IS NULL AND archived_at IS NULL
                      AND NOT temporary
                    ORDER BY updated_at DESC LIMIT :limit)
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("limit", limit).update();
    }

    /** A conversation someone writes in is not archived, so the turn that reserves a reply takes it back out. */
    public void unarchiveOnActivity(UUID session) {
        jdbc.sql("UPDATE chat_session SET archived_at = NULL WHERE id = :session AND archived_at IS NOT NULL")
                .param("session", session).update();
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

    /** The end of the selected branch: the root when nothing was written, never further than a history can be. */
    public UUID leaf(ChatSession session) {
        return jdbc.sql("""
                        WITH RECURSIVE branch AS (
                            SELECT m.id, m.latest_child_message_id, 0 AS depth FROM chat_message m
                            WHERE session_id = :session AND id = :root
                            UNION ALL
                            SELECT m.id, m.latest_child_message_id, b.depth + 1 FROM branch b
                            JOIN chat_message m ON m.id = b.latest_child_message_id AND m.session_id = :session
                            WHERE b.depth < 10000
                        ) SELECT id FROM branch ORDER BY depth DESC LIMIT 1
                        """).param("session", session.id()).param("root", session.rootMessageId())
                .query(UUID.class).single();
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
                        List.of(JSON.readValue(row.getString("file_ids"), UUID[].class)), WebSearchMode.valueOf(row.getString("web_search")),
                        row.getBoolean("deep_research"))).optional();
    }

    public record ReservedRequest(UUID userMessageId, UUID parentMessageId, String content, UUID assistantMessageId,
                                  @Nullable UUID requestedModelId, @Nullable UUID selectedModelId, @Nullable String fallbackReason,
                                  ChatCommand.Operation operation, List<UUID> fileIds, WebSearchMode webSearch,
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
     * @param reasoningEffort the level pinned on this conversation, else the agent's own; either outranks the model
     *                        configuration (MEM-273)
     */
    public record Persona(String instructions, ChatTurnOptions options, String revision,
                          @Nullable UUID modelConfigurationId, List<UUID> fileIds, Set<String> tools,
                          @Nullable List<UUID> mcpServerIds,
                          @Nullable ReasoningEffort reasoningEffort) {
        public Persona(String instructions, ChatTurnOptions options, String revision,
                       @Nullable UUID modelConfigurationId, List<UUID> fileIds, Set<String> tools,
                       @Nullable List<UUID> mcpServerIds) {
            this(instructions, options, revision, modelConfigurationId, fileIds, tools, mcpServerIds, null);
        }
    }

    /** Pins or clears the reasoning level of one conversation the caller owns. */
    public void saveReasoningEffort(TenantId tenant, ActorId actor, UUID session,
                                    @Nullable ReasoningEffort effort) {
        jdbc.sql("""
                        UPDATE chat_session SET reasoning_effort = :effort
                        WHERE id = :session AND tenant_id = :tenant AND owner_actor_id = :actor AND deleted_at IS NULL
                        """)
                .param("session", session).param("tenant", tenant.value()).param("actor", actor.value())
                .param("effort", effort == null ? null : effort.name())
                .update();
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

    /**
     * Who may run a turn with the session's agent: whoever may use it, or the owner of a conversation held in a
     * channel, whose bot was bound to that agent by someone allowed to (MEM-273). The documents the turn reads stay
     * the owner's own: Sources and Document Sets are resolved for {@code s.owner_actor_id} below and in retrieval.
     */
    private static final String CHANNEL_OR_USES = "(s.surface = 'CHANNEL' OR "
            + AgentAccessSql.USES.replace(":actor", "s.owner_actor_id") + ")";

    /** The session, its agent and its Project, as {@link #persona} and {@link #personaRevision} read them. */
    private static final String SESSION_AGENT = """
            FROM chat_session s JOIN persona p ON s.persona_id=p.id AND s.tenant_id=p.tenant_id
            LEFT JOIN chat_project pr ON pr.id=s.project_id AND pr.tenant_id=s.tenant_id AND pr.owner_actor_id=s.owner_actor_id
            WHERE s.id=:session AND s.deleted_at IS NULL AND p.deleted_at IS NULL AND
            """ + CHANNEL_OR_USES;

    /** The Tenant's Chat settings are part of it because the turn reads grounded answers from them (MEM-195). */
    private static final String REVISION = "concat_ws(':',p.id,p.revision,p.model_revision,pr.id,pr.revision,"
            + "(SELECT cs.revision FROM chat_settings cs WHERE cs.tenant_id = p.tenant_id))";

    /**
     * Everything a turn takes from its agent, in one statement: tools, MCP servers and the Sources it searches are
     * aggregated in place. Agent and Project edits advance {@code revision}, which {@link #personaRevision} rechecks.
     */
    public Persona persona(UUID session, boolean lock, boolean agentsManage) {
        return jdbc.sql("""
                        SELECT p.builtin_key, p.model_configuration_id,
                            COALESCE(s.reasoning_effort, p.reasoning_effort) AS reasoning_effort,
                            p.grounded OR COALESCE((SELECT cs.grounded_answers FROM chat_settings cs
                                                    WHERE cs.tenant_id = p.tenant_id), FALSE) AS grounded,
                            CASE WHEN p.builtin_key IS NULL THEN p.file_ids ELSE COALESCE(pr.file_ids,'[]'::jsonb) END AS file_ids,
                            %s AS revision,
                            CASE WHEN p.builtin_key IS NULL THEN concat_ws(chr(10), :base, p.instructions)
                                 WHEN pr.id IS NOT NULL THEN concat_ws(chr(10), p.instructions, pr.instructions)
                                 ELSE p.instructions END AS instructions,
                            ARRAY(SELECT tool.tool_key FROM persona_tool tool WHERE tool.persona_id = p.id) AS tools,
                            ARRAY(SELECT server.server_id FROM persona_mcp_server server WHERE server.persona_id = p.id
                                  ORDER BY server.server_id) AS mcp_servers,
                            EXISTS (SELECT 1 FROM persona_source direct WHERE direct.persona_id = p.id)
                                OR EXISTS (SELECT 1 FROM persona_document_set attachment
                                           JOIN document_set d ON d.tenant_id = attachment.tenant_id AND d.id = attachment.document_set_id
                                           WHERE attachment.persona_id = p.id AND d.deleted_at IS NULL) AS restricts_sources,
                            ARRAY(SELECT direct.source_id FROM persona_source direct WHERE direct.persona_id = p.id
                                  UNION
                                  SELECT source.source_id FROM persona_document_set attachment
                                  JOIN document_set d ON d.tenant_id = attachment.tenant_id AND d.id = attachment.document_set_id
                                  JOIN document_set_source source ON source.tenant_id = d.tenant_id AND source.document_set_id = d.id
                                  WHERE attachment.persona_id = p.id AND d.deleted_at IS NULL AND %s
                                  ORDER BY 1) AS sources
                        """.formatted(REVISION, DocumentSetAccessSql.USES.replace(":actor", "s.owner_actor_id"))
                        + SESSION_AGENT + (lock ? " FOR SHARE OF p" : ""))
                .param("session", session).param("base", ChatPrompts.DEFAULT_SYSTEM)
                .param("agentsManage", agentsManage)
                .query((row, ignored) -> {
                    boolean builtin = row.getString("builtin_key") != null;
                    var tools = Set.copyOf(List.of((String[]) row.getArray("tools").getArray()));
                    String effort = row.getString("reasoning_effort");
                    return new Persona(row.getString("instructions"),
                            ChatTurnOptions.builder()
                                    .searchEnabled(tools.contains("search"))
                                    .sourceIds(List.of((UUID[]) row.getArray("sources").getArray()))
                                    .sourcesRestricted(row.getBoolean("restricts_sources"))
                                    .codeInterpreter(tools.contains("code_interpreter"))
                                    .grounded(row.getBoolean("grounded"))
                                    .build(),
                            row.getString("revision"), row.getObject("model_configuration_id", UUID.class),
                            files(builtin, tools, List.of(JSON.readValue(row.getString("file_ids"), UUID[].class))), tools,
                            builtin ? null : List.of((UUID[]) row.getArray("mcp_servers").getArray()),
                            effort == null ? null : ReasoningEffort.valueOf(effort));
                })
                .optional().orElseThrow(ChatException::unavailable);
    }

    /**
     * The files a turn reads for its agent. An agent's documents switch is its {@code search} tool, and turning it off
     * turns off its files too (Onyx drops every knowledge attachment when "Use Knowledge" is off); the stored list is
     * kept, so turning the switch back on restores it. The builtin agent's files are its Project's, which the switch
     * does not govern.
     */
    private static List<UUID> files(boolean builtin, Set<String> tools, List<UUID> fileIds) {
        return builtin || tools.contains("search") ? fileIds : List.of();
    }

    /**
     * An assistant's unsaved settings, already checked by its editor's Save rules (MEM-273). {@code mcpServerIds} is
     * null for the builtin agent, which reaches every accessible server.
     */
    public record DraftAgent(String instructions, Set<String> tools, @Nullable List<UUID> mcpServerIds,
                             List<UUID> sourceIds, List<UUID> documentSetIds, @Nullable UUID modelConfigurationId,
                             @Nullable ReasoningEffort reasoningEffort, List<UUID> fileIds, boolean grounded) {
        public DraftAgent {
            tools = Set.copyOf(tools);
            mcpServerIds = mcpServerIds == null ? null : List.copyOf(mcpServerIds);
            sourceIds = List.copyOf(sourceIds);
            documentSetIds = List.copyOf(documentSetIds);
            fileIds = List.copyOf(fileIds);
        }
    }

    /**
     * A turn's agent built from unsaved settings, as {@link #persona} builds it from the saved ones: the session must
     * still be usable under its owner's authority, its pinned level outranks the draft's, the Tenant's grounded
     * answers still apply and Document Sets add only the Sources the owner may use. The revision is the saved
     * assistant's, so the reservation's share-locked recheck still holds.
     */
    public Persona draftPersona(UUID session, boolean agentsManage, DraftAgent draft) {
        boolean sets = !draft.documentSetIds().isEmpty();
        String attached = sets ? "d.id IN (:sets)" : "FALSE";
        var statement = jdbc.sql("""
                        SELECT p.builtin_key, s.reasoning_effort AS pinned_effort,
                            COALESCE((SELECT cs.grounded_answers FROM chat_settings cs
                                      WHERE cs.tenant_id = p.tenant_id), FALSE) AS tenant_grounded,
                            %s AS revision,
                            EXISTS (SELECT 1 FROM document_set d
                                    WHERE d.tenant_id = p.tenant_id AND %s AND d.deleted_at IS NULL) AS restricts_sets,
                            ARRAY(SELECT source.source_id FROM document_set d
                                  JOIN document_set_source source ON source.tenant_id = d.tenant_id AND source.document_set_id = d.id
                                  WHERE d.tenant_id = p.tenant_id AND %s AND d.deleted_at IS NULL AND %s
                                  ORDER BY 1) AS set_sources
                        """.formatted(REVISION, attached, attached,
                        DocumentSetAccessSql.USES.replace(":actor", "s.owner_actor_id"))
                        + SESSION_AGENT)
                .param("session", session).param("agentsManage", agentsManage);
        if (sets) statement = statement.param("sets", draft.documentSetIds());
        return statement.query((row, ignored) -> {
                    boolean builtin = row.getString("builtin_key") != null;
                    var sources = new TreeSet<>(draft.sourceIds());
                    sources.addAll(List.of((UUID[]) row.getArray("set_sources").getArray()));
                    String pinned = row.getString("pinned_effort");
                    return new Persona(builtin ? draft.instructions()
                            : String.join("\n", ChatPrompts.DEFAULT_SYSTEM, draft.instructions()),
                            ChatTurnOptions.builder()
                                    .searchEnabled(draft.tools().contains("search"))
                                    .sourceIds(List.copyOf(sources))
                                    .sourcesRestricted(!draft.sourceIds().isEmpty() || row.getBoolean("restricts_sets"))
                                    .codeInterpreter(draft.tools().contains("code_interpreter"))
                                    .grounded(draft.grounded() || row.getBoolean("tenant_grounded"))
                                    .build(),
                            row.getString("revision"), draft.modelConfigurationId(),
                            files(builtin, draft.tools(), draft.fileIds()), draft.tools(),
                            builtin ? null : draft.mcpServerIds() == null ? List.of() : draft.mcpServerIds(),
                            pinned != null ? ReasoningEffort.valueOf(pinned) : draft.reasoningEffort());
                })
                .optional().orElseThrow(ChatException::unavailable);
    }

    /**
     * The revision of the agent a turn was prepared with, share-locked for the reserving transaction; the owner must
     * still be allowed to use it. A turn that carries its agent compares this instead of reading the agent again.
     */
    public String personaRevision(UUID session, boolean agentsManage) {
        return jdbc.sql("SELECT " + REVISION + " AS revision " + SESSION_AGENT + " FOR SHARE OF p")
                .param("session", session).param("agentsManage", agentsManage)
                .query(String.class).optional().orElseThrow(ChatException::unavailable);
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
                .param("id", expected.id()).param("updated", Timestamp.from(expected.updatedAt()))
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

    /** A conversation that leaves no history cannot be put in a Project or shared; both commands refuse it. */
    public boolean temporary(UUID session) {
        return jdbc.sql("SELECT temporary FROM chat_session WHERE id = :session")
                .param("session", session).query(Boolean.class).optional().orElse(false);
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
        return jdbc.sql(SELECT_SESSION + """
                FROM chat_session WHERE tenant_id=:tenant AND owner_actor_id=:actor
                    AND project_id=:project AND deleted_at IS NULL AND""" + asked("chat_session") + """

                ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("project", project)
                .param("limit", limit).param("offset", offset).query(JdbcChatRepository::session).list();
    }

    public Optional<ChatSession> shared(TenantId tenant, UUID session) {
        return jdbc.sql("""
                SELECT s.*, EXISTS (SELECT 1 FROM chat_message r WHERE r.session_id = s.id AND r.status = 'RUNNING') AS reply_running
                FROM chat_session s JOIN chat_sharing h ON h.session_id=s.id AND h.tenant_id=s.tenant_id
                WHERE s.tenant_id=:tenant AND s.id=:session AND s.deleted_at IS NULL AND h.enabled
                """).param("tenant", tenant.value()).param("session", session).query(JdbcChatRepository::session).optional();
    }

    /**
     * Ancestors only, including the current user; newest first for bounded context selection.
     */
    /** MEM-287: an answer's tool calls, in call order. */
    public void saveToolCalls(UUID message, List<ChatToolTranscript.Call> calls) {
        for (int position = 0; position < calls.size(); position++) {
            var call = calls.get(position);
            jdbc.sql("""
                    INSERT INTO chat_message_tool_calls (message_id, position, call_id, tool_name, arguments, result)
                    VALUES (:message, :position, :callId, :name, :arguments, :result)
                    ON CONFLICT (message_id, position) DO NOTHING
                    """).param("message", message).param("position", position).param("callId", call.callId())
                    .param("name", call.name()).param("arguments", call.arguments())
                    .param("result", call.result(), Types.VARCHAR).update();
        }
    }

    /** The tool calls of {@code messages}, each answer's in call order; an answer without calls is absent. */
    public Map<UUID, List<ChatToolTranscript.Call>> toolCalls(Collection<UUID> messages) {
        if (messages.isEmpty()) return Map.of();
        var calls = new LinkedHashMap<UUID, List<ChatToolTranscript.Call>>();
        jdbc.sql("""
                SELECT message_id, call_id, tool_name, arguments, result FROM chat_message_tool_calls
                WHERE message_id IN (:messages) ORDER BY message_id, position
                """).param("messages", messages).query(row -> {
            calls.computeIfAbsent(row.getObject("message_id", UUID.class), ignored -> new ArrayList<>())
                    .add(new ChatToolTranscript.Call(row.getString("call_id"), row.getString("tool_name"),
                            row.getString("arguments"), row.getString("result")));
        });
        return calls;
    }

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
    public Set<UUID> renewLeases(Collection<UUID> assistants, Duration lease) {
        if (assistants.isEmpty()) return Set.of();
        return Set.copyOf(jdbc.sql("""
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
        return finish(session, assistant, status, content, failure, model, input, output, cost, sources,
                ChatActivity.EMPTY, ChatResearch.EMPTY);
    }

    public boolean finish(UUID session, UUID assistant, Status status, String content,
                          @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                          @Nullable Double cost, List<ChatSource> sources, ChatActivity activity,
                          ChatResearch research) {
        return terminal(session, assistant, status, content, failure, model, input, output, cost, sources, activity, research, null)
                .isPresent();
    }

    /**
     * Writes the terminal outcome and returns the reply's terminal winner: the outcome written here, or, when the
     * reply had already ended, the one that ended it.
     */
    public Control finishAndRead(UUID session, UUID assistant, Status status, String content,
                                 @Nullable String failure, @Nullable String model, @Nullable Long input, @Nullable Long output,
                                 @Nullable Double cost, List<ChatSource> sources, ChatActivity activity,
                                 ChatResearch research, @Nullable String refusal) {
        return terminal(session, assistant, status, content, failure, model, input, output, cost, sources, activity, research, refusal)
                .orElseGet(() -> control(assistant));
    }

    private Optional<Control> terminal(UUID session, UUID assistant, Status status, String content,
                                       @Nullable String failure, @Nullable String model, @Nullable Long input,
                                       @Nullable Long output, @Nullable Double cost, List<ChatSource> sources,
                                       ChatActivity activity, ChatResearch research, @Nullable String refusal) {
        // Same lock order as reserve/Stop: session, then message. Reversing it can deadlock terminal races.
        if (jdbc.sql("SELECT id FROM chat_session WHERE id = :session FOR UPDATE").param("session", session)
                .query(UUID.class).optional().isEmpty()) return Optional.empty();
        // A lapsed but unreconciled lease is not a failure: a live process finishing proves it was alive.
        // Once reconciliation has failed the row, the RUNNING predicate rejects this late write.
        var written = jdbc.sql("""
                        UPDATE chat_message SET status = :status, failure_code = :failure,
                            content = :content, model_name = :model, input_tokens = :input, output_tokens = :output,
                            cost_usd = :cost, sources = CAST(:sources AS jsonb),
                            activity = CAST(:activity AS jsonb), is_clarification = :clarification, research_plan = :plan,
                            research_agents = CAST(:agents AS jsonb), refusal_reason = :refusal,
                            finished_at = clock_timestamp()
                        WHERE session_id = :session AND id = :id AND role = 'ASSISTANT' AND status = 'RUNNING'
                        RETURNING status, failure_code
                        """).param("session", session).param("id", assistant).param("status", status.name())
                .param("content", content).param("failure", failure, Types.VARCHAR)
                .param("model", model, Types.VARCHAR).param("input", input, Types.BIGINT)
                .param("output", output, Types.BIGINT).param("cost", cost, Types.DOUBLE)
                .param("sources", JSON.writeValueAsString(sources))
                .param("activity", JSON.writeValueAsString(activity)).param("clarification", research.clarification())
                .param("plan", research.plan(), Types.VARCHAR).param("agents", JSON.writeValueAsString(research.agents()))
                .param("refusal", refusal, Types.VARCHAR)
                .query((row, ignored) -> new Control(Status.valueOf(row.getString("status")), row.getString("failure_code")))
                .optional();
        if (written.isPresent()) touch(session);
        return written;
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
        String effort = row.getString("reasoning_effort");
        var archived = row.getTimestamp("archived_at");
        return new ChatSession(row.getObject("id", UUID.class), row.getObject("persona_id", UUID.class),
                row.getObject("root_message_id", UUID.class), row.getString("title"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                row.getObject("project_id", UUID.class),
                effort == null ? null : ReasoningEffort.valueOf(effort),
                archived == null ? null : archived.toInstant(), row.getObject("branched_from_session_id", UUID.class),
                row.getObject("branched_from_message_id", UUID.class), row.getBoolean("temporary"),
                row.getBoolean("reply_running"));
    }

    private static ChatMessage message(ResultSet row, int ignored) throws SQLException {
        var finished = row.getTimestamp("finished_at");
        return new ChatMessage(row.getObject("id", UUID.class), row.getObject("session_id", UUID.class),
                row.getObject("parent_message_id", UUID.class), row.getObject("latest_child_message_id", UUID.class),
                Role.valueOf(row.getString("role")), row.getString("content"), Status.valueOf(row.getString("status")),
                row.getTimestamp("created_at").toInstant(), finished == null ? null : finished.toInstant(),
                List.of(JSON.readValue(row.getString("sources"), ChatSource[].class)),
                List.of(JSON.readValue(row.getString("files"), ChatFileDescriptor[].class)),
                List.of(JSON.readValue(row.getString("artifacts"), ChatArtifact[].class)),
                JSON.readValue(row.getString("activity"), ChatActivity.class),
                new ChatResearch(row.getBoolean("is_clarification"), row.getString("research_plan"),
                        List.of(JSON.readValue(row.getString("research_agents"), ChatResearch.Agent[].class))),
                row.getString("failure_code"), row.getString("refusal_reason"), context(row));
    }

    private static @Nullable ChatContextUse context(ResultSet row) throws SQLException {
        int tokens = row.getInt("context_tokens");
        if (row.wasNull()) return null;
        int limit = row.getInt("context_limit");
        return row.wasNull() ? null : new ChatContextUse(tokens, limit);
    }

    /** What the reply's last model call carried; recorded once the reply has its terminal state (MEM-267). */
    public void recordContext(UUID session, UUID assistant, ChatContextUse context) {
        jdbc.sql("""
                        UPDATE chat_message SET context_tokens = :tokens, context_limit = :limit
                        WHERE session_id = :session AND id = :id AND role = 'ASSISTANT' AND status <> 'RUNNING'
                        """).param("tokens", context.tokens()).param("limit", context.limit())
                .param("session", session).param("id", assistant).update();
    }
}
