package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceDetail;
import io.memoryos.connector.SourceException;
import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceItemId;
import io.memoryos.connector.SourceItemStatus;
import io.memoryos.connector.SourceItemView;
import io.memoryos.connector.SourceOperationId;
import io.memoryos.connector.SourceStatus;
import io.memoryos.connector.SourceSummary;
import io.memoryos.connector.SourceType;
import io.memoryos.organization.OrganizationId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

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
                   pair.status,
                   pair.document_count,
                   pair.last_succeeded_at,
                   pair.error_code
            FROM connector_credential_pairs pair
            JOIN connectors connector
              ON connector.organization_id = pair.organization_id
             AND connector.id = pair.connector_id
            """;

    private final JdbcClient jdbcClient;

    public JdbcSourceQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public List<SourceSummary> list(OrganizationId organizationId) {
        return jdbcClient.sql(SOURCE_SELECT + """
                        WHERE pair.organization_id = :organizationId
                        ORDER BY connector.created_at, pair.id
                        """)
                .param("organizationId", organizationId.value())
                .query(JdbcSourceQueryRepository::summary)
                .list();
    }

    public SourceDetail detail(OrganizationId organizationId, SourceId sourceId) {
        SourceSummary source = jdbcClient.sql(SOURCE_SELECT + """
                        WHERE pair.organization_id = :organizationId AND pair.id = :pairId
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .query(JdbcSourceQueryRepository::summary)
                .optional()
                .orElseThrow(SourceException::notFound);
        return new SourceDetail(source, items(organizationId, sourceId));
    }

    public SourceItemView item(OrganizationId organizationId, SourceId sourceId, SourceItemId itemId) {
        return items(organizationId, sourceId).stream()
                .filter(item -> item.id().equals(itemId))
                .findFirst()
                .orElseThrow(SourceException::notFound);
    }

    private List<SourceItemView> items(OrganizationId organizationId, SourceId sourceId) {
        return jdbcClient.sql("""
                        SELECT item.id,
                               version.filename,
                               item.content_sha256,
                               version.size_bytes,
                               item.status,
                               item.created_at,
                               attempt.id AS attempt_id,
                               attempt.error_code
                        FROM connector_credential_pairs pair
                        JOIN connector_items item
                          ON item.organization_id = pair.organization_id
                         AND item.connector_id = pair.connector_id
                        JOIN connector_item_versions version
                          ON version.organization_id = item.organization_id
                         AND version.id = item.current_version_id
                        LEFT JOIN index_attempts attempt
                          ON attempt.organization_id = pair.organization_id
                         AND attempt.connector_credential_pair_id = pair.id
                         AND attempt.connector_item_id = item.id
                         AND attempt.pair_sequence = (
                             SELECT MAX(latest.pair_sequence)
                             FROM index_attempts latest
                             WHERE latest.organization_id = pair.organization_id
                               AND latest.connector_credential_pair_id = pair.id
                               AND latest.connector_item_id = item.id
                         )
                        WHERE pair.organization_id = :organizationId AND pair.id = :pairId
                        ORDER BY item.created_at, item.id
                        """)
                .param("organizationId", organizationId.value())
                .param("pairId", sourceId.value())
                .query(JdbcSourceQueryRepository::item)
                .list();
    }

    private static SourceSummary summary(ResultSet resultSet, int ignored) throws SQLException {
        SourceStatus status = SourceStatus.valueOf(resultSet.getString("status"));
        return new SourceSummary(
                new SourceId(resultSet.getObject("source_id", UUID.class)),
                resultSet.getString("name"),
                SourceType.valueOf(resultSet.getString("connector_type")),
                SourceAccess.valueOf(resultSet.getString("access_type")),
                status,
                status == SourceStatus.INDEXING || status == SourceStatus.DELETING,
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
                attemptId == null ? null : new SourceOperationId(attemptId),
                resultSet.getString("error_code")
        );
    }
}
