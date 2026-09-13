package io.memoryos.chat.persistence;

import io.memoryos.chat.ChatSession;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Private session search projection; both match arms enforce the same owner boundary. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatSearchRepository {
    private final JdbcClient jdbc;

    public JdbcChatSearchRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<ChatSession> search(TenantId tenant, ActorId actor, String query, int offset, int limit) {
        return jdbc.sql("""
                WITH terms AS (SELECT tsvector_to_array(to_tsvector('simple', :query)) AS words), matches AS (
                    SELECT s.id FROM chat_session s
                    WHERE s.tenant_id = :tenant AND s.owner_actor_id = :actor AND s.deleted_at IS NULL
                      AND to_tsvector('simple', s.title) @@ plainto_tsquery('simple', :query)
                    UNION
                    SELECT s.id FROM chat_message m JOIN chat_session s ON s.id = m.session_id
                    WHERE s.tenant_id = :tenant AND s.owner_actor_id = :actor AND s.deleted_at IS NULL
                      AND m.role IN ('USER', 'ASSISTANT')
                      AND octet_length(m.content) <= 65536
                      AND to_tsvector('simple', CASE WHEN octet_length(m.content) <= 65536 THEN m.content ELSE '' END)
                          @@ plainto_tsquery('simple', :query)
                    UNION
                    SELECT s.id FROM chat_message m JOIN chat_session s ON s.id = m.session_id CROSS JOIN terms
                    WHERE s.tenant_id = :tenant AND s.owner_actor_id = :actor AND s.deleted_at IS NULL
                      AND m.role IN ('USER', 'ASSISTANT') AND octet_length(m.content) > 65536
                      AND cardinality(terms.words) > 0
                      AND (SELECT count(DISTINCT word)
                           FROM ts_debug('simple', m.content) token
                           CROSS JOIN LATERAL unnest(token.lexemes) AS lexemes(word)
                           WHERE word = ANY(terms.words)) = cardinality(terms.words)
                )
                SELECT s.* FROM chat_session s JOIN matches m ON m.id = s.id
                ORDER BY s.updated_at DESC, s.id LIMIT :limit OFFSET :offset
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("query", query)
                .param("limit", limit).param("offset", offset).query(JdbcChatRepository::session).list();
    }
}
