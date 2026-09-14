package io.memoryos.chat.persistence;

import io.memoryos.chat.ChatSessionMatch;
import io.memoryos.iam.identity.ActorId;
import io.memoryos.iam.tenant.TenantId;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Private session search projection; both match arms enforce the same owner boundary. */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcChatSearchRepository {
    /** One short fragment around the matched tokens; delimiters are private-use characters, never HTML. */
    private static final String HEADLINE_OPTIONS = "StartSel=" + ChatSessionMatch.MATCH_START + ", StopSel="
            + ChatSessionMatch.MATCH_END + ", MaxFragments=1, MaxWords=24, MinWords=10, ShortWord=1";

    private final JdbcClient jdbc;

    public JdbcChatSearchRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    public List<ChatSessionMatch> search(TenantId tenant, ActorId actor, String query, int offset, int limit) {
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
                ), page AS (
                    SELECT s.* FROM chat_session s JOIN matches m ON m.id = s.id
                    ORDER BY s.updated_at DESC, s.id LIMIT :limit OFFSET :offset
                )
                -- Headlines are computed for the returned page only, from the newest indexed matching message.
                SELECT page.*, hit.snippet FROM page LEFT JOIN LATERAL (
                    SELECT ts_headline('simple', m.content, plainto_tsquery('simple', :query), :headline) AS snippet
                    FROM chat_message m
                    WHERE m.session_id = page.id AND m.role IN ('USER', 'ASSISTANT')
                      AND octet_length(m.content) <= 65536
                      AND to_tsvector('simple', CASE WHEN octet_length(m.content) <= 65536 THEN m.content ELSE '' END)
                          @@ plainto_tsquery('simple', :query)
                    ORDER BY m.created_at DESC, m.id LIMIT 1
                ) hit ON TRUE
                ORDER BY page.updated_at DESC, page.id
                """).param("tenant", tenant.value()).param("actor", actor.value()).param("query", query)
                .param("headline", HEADLINE_OPTIONS).param("limit", limit).param("offset", offset)
                .query((row, number) -> new ChatSessionMatch(JdbcChatRepository.session(row, number), row.getString("snippet")))
                .list();
    }
}
