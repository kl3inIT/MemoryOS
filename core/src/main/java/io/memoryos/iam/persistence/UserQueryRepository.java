package io.memoryos.iam.persistence;

import io.memoryos.iam.AccountType;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupIdentity;
import io.memoryos.iam.GroupSystemKey;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.TenantMembershipRole;
import io.memoryos.iam.UserCounts;
import io.memoryos.iam.UserListItem;
import io.memoryos.iam.UserPage;
import io.memoryos.iam.UserQuery;
import io.memoryos.iam.UserSort;
import io.memoryos.iam.UserStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class UserQueryRepository {

    private static final String USER_ROWS = """
            WITH user_rows AS (
                SELECT membership.actor_id,
                       CAST(NULL AS UUID) AS invitation_id,
                       profile.display_name,
                       profile.email,
                       profile.email_verified,
                       profile.issuer AS profile_issuer,
                       membership.role,
                       actor.account_type,
                       membership.status,
                       CAST(NULL AS TIMESTAMP WITH TIME ZONE) AS invitation_expires_at,
                       0 AS row_kind,
                       membership.actor_id AS row_id
                FROM tenant_memberships membership
                JOIN actors actor
                  ON actor.id = membership.actor_id
                LEFT JOIN actor_profiles profile
                  ON profile.actor_id = membership.actor_id
                WHERE membership.tenant_id = :tenantId

                UNION ALL

                SELECT CAST(NULL AS UUID) AS actor_id,
                       invitation.id AS invitation_id,
                       CAST(NULL AS TEXT) AS display_name,
                       invitation.normalized_email AS email,
                       CAST(NULL AS BOOLEAN) AS email_verified,
                       CAST(NULL AS TEXT) AS profile_issuer,
                       CAST(NULL AS TEXT) AS role,
                       CAST(NULL AS TEXT) AS account_type,
                       'INVITED' AS status,
                       invitation.expires_at AS invitation_expires_at,
                       1 AS row_kind,
                       invitation.id AS row_id
                FROM tenant_invitations invitation
                WHERE invitation.tenant_id = :tenantId
                  AND invitation.status = 'PENDING'
                  AND invitation.expires_at > :now
                  AND NOT EXISTS (
                      SELECT 1
                      FROM tenant_memberships existing_membership
                      JOIN actor_profiles existing_profile
                        ON existing_profile.actor_id = existing_membership.actor_id
                      WHERE existing_membership.tenant_id = invitation.tenant_id
                        AND existing_profile.email_verified = TRUE
                        AND existing_profile.email IS NOT NULL
                        AND LOWER(BTRIM(existing_profile.email)) = invitation.normalized_email
                  )
            )
            """;

    private static final String PAGE_GROUPS = """
            SELECT group_membership.actor_id,
                   iam_group.id AS group_id,
                   iam_group.name AS group_name,
                   iam_group.system_key
            FROM iam_group_memberships group_membership
            JOIN iam_groups iam_group
              ON iam_group.tenant_id = group_membership.tenant_id
             AND iam_group.id = group_membership.group_id
            WHERE group_membership.tenant_id = :tenantId
              AND group_membership.actor_id IN (:actorIds)
            ORDER BY group_membership.actor_id,
                     CASE iam_group.system_key
                         WHEN 'ADMIN' THEN 0
                         WHEN 'BASIC' THEN 1
                         ELSE 2
                     END,
                     LOWER(iam_group.name),
                     iam_group.id
            """;

    private final JdbcClient jdbcClient;

    public UserQueryRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public UserPage findPage(TenantId tenantId, UserQuery query, Instant now) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(now, "now must not be null");

        String order = orderExpression(query.sort());
        String sql = (USER_ROWS + """
                , global_counts AS (
                    SELECT COUNT(*) FILTER (WHERE status = 'ACTIVE') AS active_count,
                           COUNT(*) FILTER (WHERE status = 'INACTIVE') AS inactive_count,
                           COUNT(*) FILTER (WHERE status = 'INVITED') AS invited_count
                    FROM user_rows
                ),
                filtered_rows AS (
                    SELECT *
                    FROM user_rows user_row
                    WHERE 1 = 1
                %s
                ),
                filtered_count AS (
                    SELECT COUNT(*) AS total_items
                    FROM filtered_rows
                ),
                paged_rows AS (
                    SELECT user_row.*,
                           ROW_NUMBER() OVER (ORDER BY %s) AS page_ordinal
                    FROM filtered_rows user_row
                    ORDER BY %s
                    LIMIT :size OFFSET :offset
                )
                SELECT page.actor_id,
                       page.invitation_id,
                       page.display_name,
                       page.email,
                       page.email_verified,
                       page.profile_issuer,
                       page.role,
                       page.account_type,
                       page.status,
                       page.invitation_expires_at,
                       counts.active_count,
                       counts.inactive_count,
                       counts.invited_count,
                       filtered_count.total_items
                FROM global_counts counts
                CROSS JOIN filtered_count
                LEFT JOIN paged_rows page ON TRUE
                ORDER BY page.page_ordinal
                """).formatted(filterClause(query), order, order);

        var statement = jdbcClient.sql(sql)
                .param("tenantId", tenantId.value())
                .param("now", Timestamp.from(now))
                .param("size", query.size())
                .param("offset", query.offset());
        if (query.search() != null) {
            statement = statement.param("search", query.search());
        }
        if (query.status() != null) {
            statement = statement.param("status", query.status().name());
        }
        if (query.role() != null) {
            statement = statement.param("role", query.role().name());
        }
        if (query.groupId() != null) {
            statement = statement.param("groupId", query.groupId().value());
        }

        List<QueryRow> rows = statement.query((resultSet, ignored) -> queryRow(resultSet)).list();
        if (rows.isEmpty()) {
            throw new IllegalStateException("Users metadata query returned no row");
        }
        QueryRow metadata = rows.getFirst();
        Map<UUID, List<GroupIdentity>> groupsByActor = findGroups(
                tenantId,
                rows.stream().map(QueryRow::actorId).filter(Objects::nonNull).toList()
        );
        List<UserListItem> items = rows.stream()
                .map(row -> row.item(groupsByActor))
                .filter(Objects::nonNull)
                .toList();
        return new UserPage(
                items,
                query.page(),
                query.size(),
                metadata.totalItems(),
                Math.ceilDiv(metadata.totalItems(), query.size()),
                new UserCounts(metadata.active(), metadata.inactive(), metadata.invited())
        );
    }

    private Map<UUID, List<GroupIdentity>> findGroups(TenantId tenantId, List<UUID> actorIds) {
        if (actorIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<GroupIdentity>> groups = new HashMap<>();
        jdbcClient.sql(PAGE_GROUPS)
                .param("tenantId", tenantId.value())
                .param("actorIds", actorIds)
                .query((resultSet, ignored) -> {
                    UUID actorId = resultSet.getObject("actor_id", UUID.class);
                    String systemKey = resultSet.getString("system_key");
                    groups.computeIfAbsent(actorId, ignoredActor -> new ArrayList<>())
                            .add(new GroupIdentity(
                                    new GroupId(resultSet.getObject("group_id", UUID.class)),
                                    resultSet.getString("group_name"),
                                    systemKey == null ? null : GroupSystemKey.valueOf(systemKey)
                            ));
                    return actorId;
                })
                .list();
        groups.replaceAll((_, actorGroups) -> List.copyOf(actorGroups));
        return Map.copyOf(groups);
    }

    private static String filterClause(UserQuery query) {
        StringBuilder filters = new StringBuilder();
        if (query.search() != null) {
            filters.append(" AND (POSITION(:search IN LOWER(COALESCE(user_row.display_name, ''))) > 0")
                    .append(" OR POSITION(:search IN LOWER(COALESCE(user_row.email, ''))) > 0)");
        }
        if (query.status() != null) {
            filters.append(" AND user_row.status = :status");
        }
        if (query.role() != null) {
            filters.append(" AND user_row.role = :role");
        }
        if (query.groupId() != null) {
            filters.append("""
                     AND user_row.actor_id IS NOT NULL
                     AND EXISTS (
                         SELECT 1
                         FROM iam_group_memberships selected_group
                         WHERE selected_group.tenant_id = :tenantId
                           AND selected_group.actor_id = user_row.actor_id
                           AND selected_group.group_id = :groupId
                     )
                    """);
        }
        return filters.toString();
    }

    private static String orderExpression(UserSort sort) {
        String selected = switch (sort) {
            case NAME_ASC -> "LOWER(COALESCE(NULLIF(BTRIM(user_row.display_name), ''), "
                    + "NULLIF(BTRIM(user_row.email), ''))) ASC NULLS LAST";
            case NAME_DESC -> "LOWER(COALESCE(NULLIF(BTRIM(user_row.display_name), ''), "
                    + "NULLIF(BTRIM(user_row.email), ''))) DESC NULLS LAST";
            case EMAIL_ASC -> "LOWER(user_row.email) ASC NULLS LAST";
            case EMAIL_DESC -> "LOWER(user_row.email) DESC NULLS LAST";
            case STATUS_ASC -> "user_row.status ASC";
            case STATUS_DESC -> "user_row.status DESC";
            case ROLE_ASC -> "user_row.role ASC NULLS LAST";
            case ROLE_DESC -> "user_row.role DESC NULLS LAST";
        };
        return selected + ", user_row.row_kind ASC, user_row.row_id ASC";
    }

    private static QueryRow queryRow(ResultSet resultSet) throws SQLException {
        return new QueryRow(
                resultSet.getObject("actor_id", UUID.class),
                resultSet.getObject("invitation_id", UUID.class),
                resultSet.getString("display_name"),
                resultSet.getString("email"),
                resultSet.getObject("email_verified", Boolean.class),
                resultSet.getString("profile_issuer"),
                resultSet.getString("role"),
                resultSet.getString("account_type"),
                resultSet.getString("status"),
                instant(resultSet),
                resultSet.getLong("active_count"),
                resultSet.getLong("inactive_count"),
                resultSet.getLong("invited_count"),
                resultSet.getLong("total_items")
        );
    }

    private static @Nullable Instant instant(ResultSet resultSet) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp("invitation_expires_at");
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record QueryRow(
            @Nullable UUID actorId,
            @Nullable UUID invitationId,
            @Nullable String displayName,
            @Nullable String email,
            @Nullable Boolean emailVerified,
            @Nullable String profileIssuer,
            @Nullable String role,
            @Nullable String accountType,
            @Nullable String status,
            @Nullable Instant invitationExpiresAt,
            long active,
            long inactive,
            long invited,
            long totalItems
    ) {
        private @Nullable UserListItem item(Map<UUID, List<GroupIdentity>> groupsByActor) {
            if (actorId == null && invitationId == null) {
                return null;
            }
            return new UserListItem(
                    actorId == null ? null : new ActorId(actorId),
                    invitationId,
                    displayName,
                    email,
                    emailVerified,
                    profileIssuer,
                    role == null ? null : TenantMembershipRole.valueOf(role),
                    accountType == null ? null : AccountType.valueOf(accountType),
                    UserStatus.valueOf(Objects.requireNonNull(status, "status must not be null")),
                    actorId == null ? List.of() : groupsByActor.getOrDefault(actorId, List.of()),
                    invitationExpiresAt
            );
        }
    }
}
