package io.memoryos.iam.persistence;

import io.memoryos.iam.AccountType;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupIdentity;
import io.memoryos.iam.GroupIdentityPage;
import io.memoryos.iam.GroupMember;
import io.memoryos.iam.GroupMemberPage;
import io.memoryos.iam.GroupQuery;
import io.memoryos.iam.GroupSystemKey;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.TenantMembershipStatus;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class GroupProjectionRepository {
    private static final String VISIBLE_GROUP_FILTER = """
              AND (
                    :globalAccess
                    OR (
                        group_record.system_key IS NULL
                        AND EXISTS (
                            SELECT 1
                            FROM iam_group_memberships manager_membership
                            WHERE manager_membership.tenant_id = group_record.tenant_id
                              AND manager_membership.group_id = group_record.id
                              AND manager_membership.actor_id = :actorId
                              AND manager_membership.is_manager
                        )
                    )
              )
            """;

    private static final String GROUP_COUNT = """
            SELECT COUNT(*)
            FROM iam_groups group_record
            WHERE group_record.tenant_id = :tenantId
              AND (NOT :hasSearch OR LOWER(group_record.name) LIKE :search ESCAPE '\\')
            """ + VISIBLE_GROUP_FILTER;

    private static final String GROUPS = """
            SELECT
                group_record.id,
                group_record.name,
                group_record.system_key,
                (
                    SELECT COUNT(*)
                    FROM iam_group_memberships membership
                    WHERE membership.tenant_id = group_record.tenant_id
                      AND membership.group_id = group_record.id
                ) AS member_count,
                (
                    SELECT COUNT(*)
                    FROM iam_group_memberships membership
                    WHERE membership.tenant_id = group_record.tenant_id
                      AND membership.group_id = group_record.id
                      AND membership.is_manager
                ) AS manager_count,
                COALESCE((
                    SELECT STRING_AGG(grant_record.capability, ',' ORDER BY grant_record.capability)
                    FROM iam_group_capability_grants grant_record
                    WHERE grant_record.tenant_id = group_record.tenant_id
                      AND grant_record.group_id = group_record.id
                      AND (
                            (group_record.system_key = 'ADMIN' AND grant_record.capability = 'IAM_ADMIN')
                            OR (
                                group_record.system_key IS NULL
                                AND grant_record.capability <> 'IAM_ADMIN'
                            )
                      )
                ), '') AS capabilities,
                EXISTS (
                    SELECT 1
                    FROM iam_group_memberships manager_membership
                    WHERE manager_membership.tenant_id = group_record.tenant_id
                      AND manager_membership.group_id = group_record.id
                      AND manager_membership.actor_id = :actorId
                      AND manager_membership.is_manager
                ) AS managed_by_actor
            FROM iam_groups group_record
            WHERE group_record.tenant_id = :tenantId
              AND (NOT :hasSearch OR LOWER(group_record.name) LIKE :search ESCAPE '\\')
            """ + VISIBLE_GROUP_FILTER + """
            ORDER BY
                CASE group_record.system_key WHEN 'ADMIN' THEN 0 WHEN 'BASIC' THEN 1 ELSE 2 END,
                LOWER(group_record.name),
                group_record.id
            OFFSET :offset ROWS FETCH FIRST :size ROWS ONLY
            """;

    private static final String GROUP_DETAIL = """
            SELECT
                group_record.id,
                group_record.name,
                group_record.system_key,
                (
                    SELECT COUNT(*)
                    FROM iam_group_memberships membership
                    WHERE membership.tenant_id = group_record.tenant_id
                      AND membership.group_id = group_record.id
                ) AS member_count,
                (
                    SELECT COUNT(*)
                    FROM iam_group_memberships membership
                    WHERE membership.tenant_id = group_record.tenant_id
                      AND membership.group_id = group_record.id
                      AND membership.is_manager
                ) AS manager_count,
                COALESCE((
                    SELECT STRING_AGG(grant_record.capability, ',' ORDER BY grant_record.capability)
                    FROM iam_group_capability_grants grant_record
                    WHERE grant_record.tenant_id = group_record.tenant_id
                      AND grant_record.group_id = group_record.id
                      AND (
                            (group_record.system_key = 'ADMIN' AND grant_record.capability = 'IAM_ADMIN')
                            OR (
                                group_record.system_key IS NULL
                                AND grant_record.capability <> 'IAM_ADMIN'
                            )
                      )
                ), '') AS capabilities,
                EXISTS (
                    SELECT 1
                    FROM iam_group_memberships manager_membership
                    WHERE manager_membership.tenant_id = group_record.tenant_id
                      AND manager_membership.group_id = group_record.id
                      AND manager_membership.actor_id = :actorId
                      AND manager_membership.is_manager
                ) AS managed_by_actor
            FROM iam_groups group_record
            WHERE group_record.tenant_id = :tenantId
              AND group_record.id = :groupId
            """ + VISIBLE_GROUP_FILTER;

    private static final String MEMBER_COUNT = """
            SELECT COUNT(*)
            FROM iam_group_memberships group_membership
            JOIN tenant_memberships tenant_membership
              ON tenant_membership.tenant_id = group_membership.tenant_id
             AND tenant_membership.actor_id = group_membership.actor_id
            LEFT JOIN actor_profiles profile
              ON profile.actor_id = group_membership.actor_id
            WHERE group_membership.tenant_id = :tenantId
              AND group_membership.group_id = :groupId
              AND (
                    NOT :hasSearch
                    OR LOWER(COALESCE(profile.display_name, '')) LIKE :search ESCAPE '\\'
                    OR LOWER(COALESCE(profile.email, '')) LIKE :search ESCAPE '\\'
              )
            """;

    private static final String MEMBERS = """
            SELECT
                group_membership.actor_id,
                profile.display_name,
                profile.email,
                actor.account_type,
                tenant_membership.status,
                group_membership.is_manager,
                tenant_membership.role = 'OWNER' AS protected_owner
            FROM iam_group_memberships group_membership
            JOIN tenant_memberships tenant_membership
              ON tenant_membership.tenant_id = group_membership.tenant_id
             AND tenant_membership.actor_id = group_membership.actor_id
            JOIN actors actor
              ON actor.id = group_membership.actor_id
            LEFT JOIN actor_profiles profile
              ON profile.actor_id = group_membership.actor_id
            WHERE group_membership.tenant_id = :tenantId
              AND group_membership.group_id = :groupId
              AND (
                    NOT :hasSearch
                    OR LOWER(COALESCE(profile.display_name, '')) LIKE :search ESCAPE '\\'
                    OR LOWER(COALESCE(profile.email, '')) LIKE :search ESCAPE '\\'
              )
            ORDER BY
                LOWER(COALESCE(NULLIF(profile.display_name, ''), profile.email, '')),
                group_membership.actor_id
            OFFSET :offset ROWS FETCH FIRST :size ROWS ONLY
            """;

    private static final String CANDIDATE_COUNT = """
            SELECT COUNT(*)
            FROM tenant_memberships tenant_membership
            JOIN actors actor
              ON actor.id = tenant_membership.actor_id
            LEFT JOIN actor_profiles profile
              ON profile.actor_id = tenant_membership.actor_id
            WHERE tenant_membership.tenant_id = :tenantId
              AND NOT EXISTS (
                    SELECT 1
                    FROM iam_group_memberships group_membership
                    WHERE group_membership.tenant_id = tenant_membership.tenant_id
                      AND group_membership.group_id = :groupId
                      AND group_membership.actor_id = tenant_membership.actor_id
              )
              AND (
                    NOT :hasSearch
                    OR LOWER(COALESCE(profile.display_name, '')) LIKE :search ESCAPE '\\'
                    OR LOWER(COALESCE(profile.email, '')) LIKE :search ESCAPE '\\'
              )
            """;

    private static final String CANDIDATES = """
            SELECT
                tenant_membership.actor_id,
                profile.display_name,
                profile.email,
                actor.account_type,
                tenant_membership.status,
                FALSE AS is_manager,
                tenant_membership.role = 'OWNER' AS protected_owner
            FROM tenant_memberships tenant_membership
            JOIN actors actor
              ON actor.id = tenant_membership.actor_id
            LEFT JOIN actor_profiles profile
              ON profile.actor_id = tenant_membership.actor_id
            WHERE tenant_membership.tenant_id = :tenantId
              AND NOT EXISTS (
                    SELECT 1
                    FROM iam_group_memberships group_membership
                    WHERE group_membership.tenant_id = tenant_membership.tenant_id
                      AND group_membership.group_id = :groupId
                      AND group_membership.actor_id = tenant_membership.actor_id
              )
              AND (
                    NOT :hasSearch
                    OR LOWER(COALESCE(profile.display_name, '')) LIKE :search ESCAPE '\\'
                    OR LOWER(COALESCE(profile.email, '')) LIKE :search ESCAPE '\\'
              )
            ORDER BY
                LOWER(COALESCE(NULLIF(profile.display_name, ''), profile.email, '')),
                tenant_membership.actor_id
            OFFSET :offset ROWS FETCH FIRST :size ROWS ONLY
            """;

    private static final String OPTION_COUNT = """
            SELECT COUNT(*)
            FROM iam_groups group_record
            WHERE group_record.tenant_id = :tenantId
              AND (NOT :hasSearch OR LOWER(group_record.name) LIKE :search ESCAPE '\\')
            """;

    private static final String OPTIONS = """
            SELECT group_record.id, group_record.name, group_record.system_key
            FROM iam_groups group_record
            WHERE group_record.tenant_id = :tenantId
              AND (NOT :hasSearch OR LOWER(group_record.name) LIKE :search ESCAPE '\\')
            ORDER BY
                CASE group_record.system_key WHEN 'ADMIN' THEN 0 WHEN 'BASIC' THEN 1 ELSE 2 END,
                LOWER(group_record.name),
                group_record.id
            OFFSET :offset ROWS FETCH FIRST :size ROWS ONLY
            """;

    private final JdbcClient jdbcClient;

    public GroupProjectionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public GroupRecordPage list(
            TenantId tenantId,
            ActorId actorId,
            boolean globalAccess,
            GroupQuery query
    ) {
        Search search = Search.from(query);
        long totalItems = groupQuery(GROUP_COUNT, tenantId, actorId, globalAccess, search)
                .query(Long.class)
                .single();
        List<GroupRecord> items = groupQuery(GROUPS, tenantId, actorId, globalAccess, search)
                .param("offset", (long) query.page() * query.size())
                .param("size", query.size())
                .query(GroupProjectionRepository::group)
                .list();
        return new GroupRecordPage(items, query.page(), query.size(), totalItems);
    }

    public Optional<GroupRecord> detail(
            TenantId tenantId,
            ActorId actorId,
            GroupId groupId,
            boolean globalAccess
    ) {
        return jdbcClient.sql(GROUP_DETAIL)
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .param("groupId", groupId.value())
                .param("globalAccess", globalAccess)
                .query(GroupProjectionRepository::group)
                .optional();
    }

    public GroupMemberPage members(TenantId tenantId, GroupId groupId, GroupQuery query) {
        return memberPage(MEMBER_COUNT, MEMBERS, tenantId, groupId, query);
    }

    public GroupMemberPage candidates(TenantId tenantId, GroupId groupId, GroupQuery query) {
        return memberPage(CANDIDATE_COUNT, CANDIDATES, tenantId, groupId, query);
    }

    public GroupIdentityPage listOptions(TenantId tenantId, GroupQuery query) {
        Search search = Search.from(query);
        long totalItems = optionQuery(OPTION_COUNT, tenantId, search).query(Long.class).single();
        List<GroupIdentity> items = optionQuery(OPTIONS, tenantId, search)
                .param("offset", (long) query.page() * query.size())
                .param("size", query.size())
                .query(GroupProjectionRepository::identity)
                .list();
        return new GroupIdentityPage(
                items,
                query.page(),
                query.size(),
                totalItems,
                totalPages(totalItems, query.size())
        );
    }

    private GroupMemberPage memberPage(
            String countStatement,
            String itemStatement,
            TenantId tenantId,
            GroupId groupId,
            GroupQuery query
    ) {
        Search search = Search.from(query);
        long totalItems = memberQuery(countStatement, tenantId, groupId, search)
                .query(Long.class)
                .single();
        List<GroupMember> items = memberQuery(itemStatement, tenantId, groupId, search)
                .param("offset", (long) query.page() * query.size())
                .param("size", query.size())
                .query(GroupProjectionRepository::member)
                .list();
        return new GroupMemberPage(
                items,
                query.page(),
                query.size(),
                totalItems,
                totalPages(totalItems, query.size())
        );
    }

    private JdbcClient.StatementSpec groupQuery(
            String statement,
            TenantId tenantId,
            ActorId actorId,
            boolean globalAccess,
            Search search
    ) {
        return jdbcClient.sql(statement)
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .param("globalAccess", globalAccess)
                .param("hasSearch", search.present())
                .param("search", search.pattern());
    }

    private JdbcClient.StatementSpec memberQuery(
            String statement,
            TenantId tenantId,
            GroupId groupId,
            Search search
    ) {
        return jdbcClient.sql(statement)
                .param("tenantId", tenantId.value())
                .param("groupId", groupId.value())
                .param("hasSearch", search.present())
                .param("search", search.pattern());
    }

    private JdbcClient.StatementSpec optionQuery(String statement, TenantId tenantId, Search search) {
        return jdbcClient.sql(statement)
                .param("tenantId", tenantId.value())
                .param("hasSearch", search.present())
                .param("search", search.pattern());
    }

    private static GroupRecord group(ResultSet resultSet, int rowNumber) throws SQLException {
        String systemKey = resultSet.getString("system_key");
        return new GroupRecord(
                new GroupId(resultSet.getObject("id", UUID.class)),
                resultSet.getString("name"),
                systemKey == null ? null : GroupSystemKey.valueOf(systemKey),
                resultSet.getLong("member_count"),
                resultSet.getLong("manager_count"),
                capabilities(resultSet.getString("capabilities")),
                resultSet.getBoolean("managed_by_actor")
        );
    }

    private static GroupMember member(ResultSet resultSet, int rowNumber) throws SQLException {
        return new GroupMember(
                new ActorId(resultSet.getObject("actor_id", UUID.class)),
                resultSet.getString("display_name"),
                resultSet.getString("email"),
                AccountType.valueOf(resultSet.getString("account_type")),
                TenantMembershipStatus.valueOf(resultSet.getString("status")),
                resultSet.getBoolean("is_manager"),
                resultSet.getBoolean("protected_owner")
        );
    }

    private static GroupIdentity identity(ResultSet resultSet, int rowNumber) throws SQLException {
        String systemKey = resultSet.getString("system_key");
        return new GroupIdentity(
                new GroupId(resultSet.getObject("id", UUID.class)),
                resultSet.getString("name"),
                systemKey == null ? null : GroupSystemKey.valueOf(systemKey)
        );
    }

    private static Set<IamCapability> capabilities(String value) {
        if (value.isEmpty()) {
            return Set.of();
        }
        EnumSet<IamCapability> capabilities = EnumSet.noneOf(IamCapability.class);
        Arrays.stream(value.split(",")).map(IamCapability::valueOf).forEach(capabilities::add);
        return Collections.unmodifiableSet(capabilities);
    }

    private static long totalPages(long totalItems, int size) {
        return totalItems / size + (totalItems % size == 0 ? 0 : 1);
    }

    public record GroupRecordPage(
            List<GroupRecord> items,
            int page,
            int size,
            long totalItems
    ) {
        public GroupRecordPage {
            items = List.copyOf(items);
        }

        public long totalPages() {
            return GroupProjectionRepository.totalPages(totalItems, size);
        }
    }

    public record GroupRecord(
            GroupId id,
            String name,
            GroupSystemKey systemKey,
            long memberCount,
            long managerCount,
            Set<IamCapability> capabilities,
            boolean managedByActor
    ) {
        public GroupRecord {
            capabilities = Set.copyOf(capabilities);
        }
    }

    private record Search(boolean present, String pattern) {
        private static Search from(GroupQuery query) {
            if (query.search() == null) {
                return new Search(false, "%");
            }
            String escaped = query.search()
                    .toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_");
            return new Search(true, "%" + escaped + "%");
        }
    }
}
