package io.memoryos.connector.persistence;

import io.memoryos.connector.SourceAccess;
import io.memoryos.connector.SourceAction;
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
import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.TenantId;

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
                   pair.status,
                   pair.document_count,
                   pair.last_succeeded_at,
                   pair.error_code,
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

    private static final String ITEM_SELECT = """
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
              ON item.tenant_id = pair.tenant_id
             AND item.connector_id = pair.connector_id
            JOIN connector_item_versions version
              ON version.tenant_id = item.tenant_id
             AND version.id = item.current_version_id
            LEFT JOIN index_attempts attempt
              ON attempt.tenant_id = pair.tenant_id
             AND attempt.connector_credential_pair_id = pair.id
             AND attempt.connector_item_id = item.id
             AND attempt.pair_sequence = (
                 SELECT MAX(latest.pair_sequence)
                 FROM index_attempts latest
                 WHERE latest.tenant_id = pair.tenant_id
                   AND latest.connector_credential_pair_id = pair.id
                   AND latest.connector_item_id = item.id
             )
            WHERE pair.tenant_id = :tenantId AND pair.id = :pairId
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

    public SourceDetail detail(
            TenantId tenantId,
            ActorId actorId,
            SourceId sourceId,
            boolean globalRead,
            boolean globalManage,
            boolean globalDelete
    ) {
        SourceSummary source = summary(
                tenantId,
                actorId,
                sourceId,
                globalRead,
                globalManage,
                globalDelete
        );
        List<SourceItemView> items = jdbcClient.sql(ITEM_SELECT + """
                        ORDER BY item.created_at, item.id
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .query(JdbcSourceQueryRepository::item)
                .list();
        return new SourceDetail(source, items);
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
        return jdbcClient.sql(ITEM_SELECT + """
                          AND item.id = :itemId
                        """)
                .param("tenantId", tenantId.value())
                .param("pairId", sourceId.value())
                .param("itemId", itemId.value())
                .query(JdbcSourceQueryRepository::item)
                .optional()
                .orElseThrow(SourceException::notFound);
    }

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
                status == SourceStatus.INDEXING || status == SourceStatus.DELETING,
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
                attemptId == null ? null : new SourceOperationId(attemptId),
                resultSet.getString("error_code")
        );
    }
}
