package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceItemPage;
import io.memoryos.connector.SourceAction;
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
import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.TenantId;
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

    private static final List<SourceAction> SCOPED_MANAGE_ACTIONS =
            List.of(SourceAction.UPLOAD, SourceAction.REINDEX);
    private static final List<SourceAction> GLOBAL_MANAGE_ACTIONS =
            List.of(SourceAction.UPLOAD, SourceAction.REINDEX, SourceAction.MANAGE_GROUPS);
    private static final List<SourceAction> DELETE_ACTIONS =
            List.of(SourceAction.REMOVE_ITEMS, SourceAction.DELETE);
    private static final List<SourceAction> SCOPED_MANAGE_DELETE_ACTIONS =
            List.of(SourceAction.UPLOAD, SourceAction.REINDEX, SourceAction.REMOVE_ITEMS, SourceAction.DELETE);
    private static final List<SourceAction> GLOBAL_MANAGE_DELETE_ACTIONS = List.of(
            SourceAction.UPLOAD,
            SourceAction.REINDEX,
            SourceAction.REMOVE_ITEMS,
            SourceAction.DELETE,
            SourceAction.MANAGE_GROUPS
    );
    private static final String MANAGED_SOURCE_SCOPE = """
            EXISTS (
                SELECT 1
                FROM source_group_grants scoped_grant
                JOIN iam_groups scoped_group
                  ON scoped_group.tenant_id = scoped_grant.tenant_id
                 AND scoped_group.id = scoped_grant.group_id
                 AND scoped_group.system_key IS NULL
                JOIN iam_group_memberships scoped_membership
                  ON scoped_membership.tenant_id = scoped_grant.tenant_id
                 AND scoped_membership.group_id = scoped_grant.group_id
                 AND scoped_membership.actor_id = :actorId
                 AND scoped_membership.is_manager = TRUE
                WHERE scoped_grant.tenant_id = pair.tenant_id
                  AND scoped_grant.connector_credential_pair_id = pair.id
            )
            """;
    private static final String MANAGED_REQUESTED_GROUP_SCOPE = """
            EXISTS (
                SELECT 1
                FROM iam_groups scoped_group
                JOIN iam_group_memberships scoped_membership
                  ON scoped_membership.tenant_id = scoped_group.tenant_id
                 AND scoped_membership.group_id = scoped_group.id
                 AND scoped_membership.actor_id = :actorId
                 AND scoped_membership.is_manager = TRUE
                WHERE scoped_group.tenant_id = requested_grant.tenant_id
                  AND scoped_group.id = requested_grant.group_id
                  AND scoped_group.system_key IS NULL
            )
            """;
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
                       WHERE s.tenant_id = pair.tenant_id AND s.source_id = pair.id), pair.error_code) AS error_code,
                   CASE WHEN :globalManage THEN FALSE ELSE %s END AS managed_scope
            FROM connector_credential_pairs pair
            JOIN connectors connector
              ON connector.tenant_id = pair.tenant_id
             AND connector.id = pair.connector_id
            JOIN tenant_memberships requesting_membership
              ON requesting_membership.tenant_id = pair.tenant_id
             AND requesting_membership.actor_id = :actorId
             AND requesting_membership.status = 'ACTIVE'
            JOIN tenants requesting_tenant
              ON requesting_tenant.id = requesting_membership.tenant_id
             AND requesting_tenant.status = 'ACTIVE'
            JOIN actors requesting_actor
              ON requesting_actor.id = requesting_membership.actor_id
             AND requesting_actor.account_type = 'STANDARD'
            """.formatted(MANAGED_SOURCE_SCOPE);

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
                   success.completed_at AS last_indexed_at,
                   CASE WHEN doc.id IS NULL OR mapping.retrieval_eligible=FALSE THEN 'WAITING'
                        WHEN doc.searchable_generation=doc.content_generation THEN 'READY'
                        WHEN doc.search_error_code IS NOT NULL THEN 'FAILED'
                        ELSE 'INDEXING' END AS search_status
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
            LEFT JOIN documents_by_connector_credential_pair mapping
              ON mapping.tenant_id=item.tenant_id AND mapping.connector_credential_pair_id=:pairId
             AND mapping.connector_item_id=item.id
            LEFT JOIN documents doc ON doc.tenant_id=mapping.tenant_id AND doc.id=mapping.document_id
            """;

    private final JdbcClient jdbcClient;

    public JdbcSourceQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public List<SourceSummary> list(
            TenantId tenantId,
            ActorId actorId,
            boolean globalRead,
            boolean globalManage,
            boolean globalDelete
    ) {
        return jdbcClient.sql(SOURCE_SELECT + """
                        WHERE pair.tenant_id = :tenantId
                          AND (:globalRead OR %s)
                        ORDER BY connector.created_at, pair.id
                        """.formatted(MANAGED_SOURCE_SCOPE))
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .param("globalRead", globalRead)
                .param("globalManage", globalManage)
                .query((resultSet, ignored) -> summary(resultSet, globalManage, globalDelete))
                .list();
    }

    public SourceSummary summary(
            TenantId tenantId,
            ActorId actorId,
            SourceId sourceId,
            boolean globalRead,
            boolean globalManage,
            boolean globalDelete
    ) {
        return jdbcClient.sql(SOURCE_SELECT + """
                        WHERE pair.tenant_id = :tenantId
                          AND pair.id = :pairId
                          AND (:globalRead OR %s)
                        """.formatted(MANAGED_SOURCE_SCOPE))
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .param("pairId", sourceId.value())
                .param("globalRead", globalRead)
                .param("globalManage", globalManage)
                .query((resultSet, ignored) -> summary(resultSet, globalManage, globalDelete))
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

    public List<SourceSummary> listForGroup(
            TenantId tenantId,
            ActorId actorId,
            GroupId groupId,
            boolean globalRead,
            boolean globalManage,
            boolean globalDelete
    ) {
        return jdbcClient.sql(SOURCE_SELECT + """
                        JOIN source_group_grants requested_grant
                          ON requested_grant.tenant_id = pair.tenant_id
                         AND requested_grant.connector_credential_pair_id = pair.id
                         AND requested_grant.group_id = :groupId
                        WHERE pair.tenant_id = :tenantId
                          AND (:globalRead OR %s)
                        ORDER BY connector.created_at, pair.id
                        """.formatted(MANAGED_REQUESTED_GROUP_SCOPE))
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .param("groupId", groupId.value())
                .param("globalRead", globalRead)
                .param("globalManage", globalManage)
                .query((resultSet, ignored) -> summary(resultSet, globalManage, globalDelete))
                .list();
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

    private static SourceSummary summary(
            ResultSet resultSet,
            boolean globalManage,
            boolean globalDelete
    ) throws SQLException {
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
                resultSet.getString("error_code"),
                actions(globalManage, globalDelete, resultSet.getBoolean("managed_scope"))
        );
    }

    private static List<SourceAction> actions(
            boolean globalManage,
            boolean globalDelete,
            boolean managedScope
    ) {
        if (globalManage) {
            return globalDelete ? GLOBAL_MANAGE_DELETE_ACTIONS : GLOBAL_MANAGE_ACTIONS;
        }
        if (managedScope) {
            return globalDelete ? SCOPED_MANAGE_DELETE_ACTIONS : SCOPED_MANAGE_ACTIONS;
        }
        return globalDelete ? DELETE_ACTIONS : List.of();
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
                resultSet.getString("error_code"),
                resultSet.getString("search_status")
        );
    }
}
