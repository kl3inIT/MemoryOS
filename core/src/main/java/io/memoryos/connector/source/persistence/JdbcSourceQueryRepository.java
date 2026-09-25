package io.memoryos.connector.source.persistence;

import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceItemPage;
import io.memoryos.connector.SourcePermissions;
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
import io.memoryos.connector.sync.persistence.WorkLeases;
import io.memoryos.shared.ActorId;
import io.memoryos.iam.GroupId;
import io.memoryos.shared.TenantId;
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

    private static final String MANAGED_REQUESTED_GROUP_SCOPE = """
            EXISTS (SELECT 1 FROM iam_group_memberships member
                JOIN iam_groups managed ON managed.tenant_id = member.tenant_id
                  AND managed.id = member.group_id AND managed.system_key IS NULL
                WHERE member.tenant_id = requested_grant.tenant_id
                  AND member.group_id = requested_grant.group_id
                  AND member.actor_id = :actorId AND member.is_manager = TRUE)
            """;
    private static final String SOURCE_SELECT = """
            SELECT pair.id AS source_id,
                   connector.name,
                   connector.connector_type,
                   pair.access_type,
                   CASE WHEN pair.status = 'PAUSED' AND (EXISTS (
                       SELECT 1 FROM source_sync_attempts sync WHERE sync.tenant_id = pair.tenant_id
                         AND sync.source_id = pair.id AND sync.status = 'IN_PROGRESS'
                         AND sync.lease_expires_at > CURRENT_TIMESTAMP
                   ) OR EXISTS (
                       SELECT 1 FROM index_attempts attempt WHERE attempt.tenant_id = pair.tenant_id
                         AND attempt.connector_credential_pair_id = pair.id AND attempt.status = 'IN_PROGRESS'
                         AND attempt.lease_expires_at > CURRENT_TIMESTAMP
                   )) THEN 'PAUSING' WHEN pair.status <> 'DELETING' AND EXISTS (
                       SELECT 1 FROM source_sync_attempts sync WHERE sync.tenant_id = pair.tenant_id
                         AND sync.source_id = pair.id AND sync.status IN ('NOT_STARTED', 'IN_PROGRESS')
                   ) THEN 'INDEXING' WHEN pair.status <> 'DELETING' AND pair.status <> 'PAUSED'
                     AND pair.sync_error_code IS NOT NULL THEN 'FAILED' ELSE pair.status END AS status,
                   EXISTS (
                       SELECT 1 FROM connector_cleanup_attempts cleanup
                       WHERE cleanup.tenant_id = pair.tenant_id AND cleanup.target_pair_id = pair.id
                         AND cleanup.status IN ('NOT_STARTED', 'IN_PROGRESS')
                   ) AS cleanup_pending,
                   pair.document_count,
                   pair.last_succeeded_at,
                   -- A synchronization failure explains the Source before an indexing failure does.
                   COALESCE(pair.sync_error_code, pair.error_code) AS error_code,
                   pair.manager_actor_id,
                   manager_profile.display_name AS manager_name,
                   CASE WHEN :globalManage THEN FALSE ELSE %s END AS managed_scope,
                   (%s AND %s) AS creator_groupless
            FROM connector_credential_pairs pair
            JOIN connectors connector
              ON connector.tenant_id = pair.tenant_id
             AND connector.id = pair.connector_id
            LEFT JOIN actor_profiles manager_profile
              ON manager_profile.actor_id = pair.manager_actor_id
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
            """.formatted(SourceScopeSql.WRITE, SourceScopeSql.ACTIVE_MANAGER, SourceScopeSql.OWNER_GROUPLESS);

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
                   doc.search_error_code,
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
                        """.formatted(SourceScopeSql.READ))
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
                        """.formatted(SourceScopeSql.READ))
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
        long totalItems = jdbcClient.sql("SELECT count(*) FROM (" + ITEM_CANDIDATES + ") items")
                .param("tenantId", tenantId.value()).param("pairId", sourceId.value())
                .query(Long.class).single();
        return new SourceItemPage(page, nextCursor, totalItems);
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
                status == SourceStatus.INDEXING || status == SourceStatus.PAUSING
                        || status == SourceStatus.DELETING || resultSet.getBoolean("cleanup_pending"),
                resultSet.getLong("document_count"),
                JdbcSourceRepository.instant(resultSet, "last_succeeded_at"),
                resultSet.getString("error_code"),
                actorId(resultSet, "manager_actor_id"),
                resultSet.getString("manager_name"),
                SourcePermissions.of(globalManage, globalDelete, resultSet.getBoolean("managed_scope"),
                        resultSet.getBoolean("creator_groupless"))
        );
    }

    private static @Nullable ActorId actorId(ResultSet resultSet, String column) throws SQLException {
        UUID value = resultSet.getObject(column, UUID.class);
        return value == null ? null : new ActorId(value);
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
                resultSet.getString("search_error_code"),
                resultSet.getString("search_status")
        );
    }
}
