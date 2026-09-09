package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceItemPage;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceItemStatus;
import io.memoryos.connector.SourceItemView;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceIndexAttemptView;
import io.memoryos.connector.SourceStatus;
import io.memoryos.connector.SourceSummary;
import io.memoryos.connector.SourceType;
import io.memoryos.tenant.TenantId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceQueryRepository {

    private static final String SOURCE_SELECT = """
            SELECT pair.id AS source_id,
                   connector.name,
                   connector.connector_type,
                   pair.access_type,
                   CASE WHEN pair.status <> 'DELETING' AND EXISTS (
                       SELECT 1 FROM source_sync_attempts sync WHERE sync.tenant_id = pair.tenant_id
                         AND sync.source_id = pair.id AND sync.status IN ('NOT_STARTED', 'IN_PROGRESS')
                   ) THEN 'INDEXING' WHEN pair.status <> 'DELETING' AND EXISTS (
                       SELECT 1 FROM google_drive_sources s WHERE s.tenant_id = pair.tenant_id
                         AND s.source_id = pair.id AND s.error_code IS NOT NULL
                   ) THEN 'FAILED' ELSE pair.status END AS status,
                   EXISTS (
                       SELECT 1 FROM connector_cleanup_attempts cleanup
                       WHERE cleanup.tenant_id = pair.tenant_id AND cleanup.target_pair_id = pair.id
                         AND cleanup.status IN ('NOT_STARTED', 'IN_PROGRESS')
                   ) AS cleanup_pending,
                   pair.document_count,
                   pair.last_succeeded_at,
                   COALESCE((SELECT s.error_code FROM google_drive_sources s
                       WHERE s.tenant_id = pair.tenant_id AND s.source_id = pair.id), pair.error_code) AS error_code
            FROM connector_credential_pairs pair
            JOIN connectors connector
              ON connector.tenant_id = pair.tenant_id
             AND connector.id = pair.connector_id
            """;

    private static final String ITEM_CANDIDATES = """
            SELECT item.id, item.tenant_id, item.current_version_id,
                   item.content_sha256, item.status, item.created_at
            FROM connector_items item
            WHERE item.tenant_id = :tenantId
              AND item.connector_id = (
                  SELECT pair.connector_id FROM connector_credential_pairs pair
                  WHERE pair.tenant_id = :tenantId AND pair.id = :pairId
              )
              AND item.current_version_id IS NOT NULL
            """;

    private static final String ITEM_PROJECTION = """
            SELECT item.id,
                   version.filename,
                   item.content_sha256,
                   version.size_bytes,
                   item.status,
                   item.created_at,
                   attempt.id AS attempt_id,
                   attempt.error_code,
                   attempt.status AS attempt_status,
                   attempt.created_at AS attempt_created_at,
                   attempt.started_at AS attempt_started_at,
                   attempt.completed_at AS attempt_completed_at,
                   attempt.filename AS attempt_filename,
                   success.completed_at AS last_indexed_at
            FROM candidate_items item
            JOIN connector_item_versions version
              ON version.tenant_id = item.tenant_id
             AND version.id = item.current_version_id
            LEFT JOIN LATERAL (
                SELECT latest.id, latest.error_code, latest.status, latest.created_at,
                       latest.started_at, latest.completed_at, attempted_version.filename
                FROM index_attempts latest
                LEFT JOIN connector_item_versions attempted_version
                  ON attempted_version.tenant_id = latest.tenant_id
                 AND attempted_version.id = latest.connector_item_version_id
                WHERE latest.tenant_id = item.tenant_id
                  AND latest.connector_credential_pair_id = :pairId
                  AND latest.connector_item_id = item.id
                ORDER BY latest.pair_sequence DESC
                LIMIT 1
            ) attempt ON TRUE
            LEFT JOIN LATERAL (
                SELECT completed_at FROM index_attempts successful
                WHERE successful.tenant_id = item.tenant_id
                  AND successful.connector_credential_pair_id = :pairId
                  AND successful.connector_item_id = item.id
                  AND successful.connector_item_version_id = item.current_version_id
                  AND successful.status = 'SUCCEEDED'
                ORDER BY successful.pair_sequence DESC
                LIMIT 1
            ) success ON TRUE
            """;

    private final JdbcClient jdbcClient;

    public JdbcSourceQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public List<SourceSummary> list(TenantId tenantId) {
        return jdbcClient.sql(SOURCE_SELECT + """
                        WHERE pair.tenant_id = :tenantId
                        ORDER BY connector.created_at, pair.id
                        """)
                .param("tenantId", tenantId.value())
                .query(JdbcSourceQueryRepository::summary)
                .list();
    }

    public SourceSummary summary(TenantId tenantId, SourceId sourceId) {
        return jdbcClient.sql(SOURCE_SELECT + """
                        WHERE pair.tenant_id = :tenantId AND pair.id = :pairId
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .query(JdbcSourceQueryRepository::summary)
                .optional()
                .orElseThrow(SourceException::notFound);
    }

    public SourceItemPage items(TenantId tenantId, SourceId sourceId, @Nullable String cursor, int size) {
        String scope = tenantId.value() + "|" + sourceId.value() + "|ITEM|";
        ItemCursor position = itemCursor(cursor, scope);
        String sql = "WITH candidate_items AS MATERIALIZED (" + ITEM_CANDIDATES
                + (position == null ? "" : " AND (item.created_at, item.id) < (:cursorTime, :cursorId)")
                + " ORDER BY item.created_at DESC, item.id DESC LIMIT :limit) "
                + ITEM_PROJECTION + " ORDER BY item.created_at DESC, item.id DESC";
        var statement = jdbcClient.sql(sql)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("limit", size + 1);
        if (position != null) {
            statement.param("cursorTime", position.createdAt()).param("cursorId", position.id());
        }
        List<SourceItemView> found = statement.query(JdbcSourceQueryRepository::item).list();
        boolean more = found.size() > size;
        var page = more ? List.copyOf(found.subList(0, size)) : found;
        String nextCursor = more ? SourceHistoryCursor.encode(
                scope, page.getLast().uploadedAt() + "|" + page.getLast().id().value()) : null;
        return new SourceItemPage(page, nextCursor);
    }

    public SourceItemView item(TenantId tenantId, SourceId sourceId, SourceItemId itemId) {
        return jdbcClient.sql("WITH candidate_items AS (" + ITEM_CANDIDATES
                        + " AND item.id = :itemId) " + ITEM_PROJECTION)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("itemId", itemId.value())
                .query(JdbcSourceQueryRepository::item)
                .optional()
                .orElseThrow(SourceException::notFound);
    }

    private static @Nullable ItemCursor itemCursor(@Nullable String token, String scope) {
        try {
            String position = SourceHistoryCursor.decode(token, scope);
            if (position == null) return null;
            String[] fields = position.split("\\|", -1);
            if (fields.length != 2) throw new IllegalArgumentException();
            OffsetDateTime createdAt = WorkLeases.sqlTime(Instant.parse(fields[0]));
            // Only finite PostgreSQL timestamp values can be positions emitted by this endpoint.
            if (createdAt.getYear() < -4712 || createdAt.getYear() > 294276) throw new IllegalArgumentException();
            return new ItemCursor(createdAt, UUID.fromString(fields[1]));
        } catch (SourceException | IllegalArgumentException | java.time.DateTimeException exception) {
            throw SourceException.invalid("The Files cursor is invalid. Reload the list.", "invalid or mismatched source item cursor");
        }
    }

    private record ItemCursor(OffsetDateTime createdAt, UUID id) {}

    private static SourceSummary summary(ResultSet resultSet, int ignored) throws SQLException {
        SourceStatus status = SourceStatus.valueOf(resultSet.getString("status"));
        return new SourceSummary(
                new SourceId(resultSet.getObject("source_id", UUID.class)),
                resultSet.getString("name"),
                SourceType.valueOf(resultSet.getString("connector_type")),
                SourceAccess.valueOf(resultSet.getString("access_type")),
                status,
                status == SourceStatus.INDEXING || status == SourceStatus.DELETING
                        || resultSet.getBoolean("cleanup_pending"),
                resultSet.getLong("document_count"),
                JdbcSourceRepository.instant(resultSet, "last_succeeded_at"),
                resultSet.getString("error_code")
        );
    }

    private static SourceItemView item(ResultSet resultSet, int ignored) throws SQLException {
        UUID attemptId = resultSet.getObject("attempt_id", UUID.class);
        return new SourceItemView(
                new SourceItemId(resultSet.getObject("id", UUID.class)),
                resultSet.getString("filename"),
                resultSet.getString("content_sha256"),
                resultSet.getLong("size_bytes"),
                SourceItemStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(),
                JdbcSourceRepository.instant(resultSet, "last_indexed_at"),
                attemptId == null ? null : new SourceIndexAttemptView(
                        new SourceOperationId(attemptId), resultSet.getString("attempt_filename"),
                        JdbcSourceRepository.operationStatus(resultSet.getString("attempt_status")),
                        resultSet.getTimestamp("attempt_created_at").toInstant(),
                        JdbcSourceRepository.instant(resultSet, "attempt_started_at"),
                        JdbcSourceRepository.instant(resultSet, "attempt_completed_at"),
                        resultSet.getString("error_code")),
                resultSet.getString("error_code")
        );
    }
}
