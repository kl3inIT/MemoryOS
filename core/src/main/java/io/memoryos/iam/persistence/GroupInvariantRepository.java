package io.memoryos.iam.persistence;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.TenantId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class GroupInvariantRepository {
    private static final String MANAGED_GROUP = """
            SELECT EXISTS (
                SELECT 1
                FROM tenant_memberships tenant_membership
                JOIN tenants tenant
                  ON tenant.id = tenant_membership.tenant_id
                 AND tenant.status = 'ACTIVE'
                JOIN actors actor
                  ON actor.id = tenant_membership.actor_id
                 AND actor.account_type = 'STANDARD'
                JOIN iam_group_memberships group_membership
                  ON group_membership.tenant_id = tenant_membership.tenant_id
                 AND group_membership.actor_id = tenant_membership.actor_id
                 AND group_membership.is_manager
                JOIN iam_groups group_record
                  ON group_record.tenant_id = group_membership.tenant_id
                 AND group_record.id = group_membership.group_id
                 AND group_record.system_key IS NULL
                WHERE tenant_membership.tenant_id = :tenantId
                  AND tenant_membership.actor_id = :actorId
                  AND tenant_membership.status = 'ACTIVE'
                  AND group_record.id = :groupId
            )
            """;

    private static final String ADMIN_STATE = """
            SELECT
                EXISTS (
                    SELECT 1
                    FROM tenant_memberships target_membership
                    WHERE target_membership.tenant_id = :tenantId
                      AND target_membership.actor_id = :actorId
                      AND target_membership.role = 'OWNER'
                ) AS configured_owner,
                EXISTS (
                    SELECT 1
                    FROM tenant_memberships target_membership
                    JOIN actors target_actor
                      ON target_actor.id = target_membership.actor_id
                     AND target_actor.account_type = 'STANDARD'
                    JOIN iam_group_memberships target_admin_membership
                      ON target_admin_membership.tenant_id = target_membership.tenant_id
                     AND target_admin_membership.actor_id = target_membership.actor_id
                    JOIN iam_groups target_admin_group
                      ON target_admin_group.tenant_id = target_admin_membership.tenant_id
                     AND target_admin_group.id = target_admin_membership.group_id
                     AND target_admin_group.system_key = 'ADMIN'
                    WHERE target_membership.tenant_id = :tenantId
                      AND target_membership.actor_id = :actorId
                      AND target_membership.status = 'ACTIVE'
                ) AS active_standard_admin,
                (
                    SELECT COUNT(*)
                    FROM tenant_memberships active_membership
                    JOIN actors active_actor
                      ON active_actor.id = active_membership.actor_id
                     AND active_actor.account_type = 'STANDARD'
                    JOIN iam_group_memberships active_admin_membership
                      ON active_admin_membership.tenant_id = active_membership.tenant_id
                     AND active_admin_membership.actor_id = active_membership.actor_id
                    JOIN iam_groups active_admin_group
                      ON active_admin_group.tenant_id = active_admin_membership.tenant_id
                     AND active_admin_group.id = active_admin_membership.group_id
                     AND active_admin_group.system_key = 'ADMIN'
                    WHERE active_membership.tenant_id = :tenantId
                      AND active_membership.status = 'ACTIVE'
                ) AS active_standard_admin_count
            """;

    private final JdbcClient jdbcClient;

    public GroupInvariantRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public Set<ActorId> existingTenantMembers(TenantId tenantId, Collection<ActorId> actorIds) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Set<ActorId> requiredActorIds = Set.copyOf(actorIds);
        if (requiredActorIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("""
                        SELECT membership.actor_id
                        FROM tenant_memberships membership
                        WHERE membership.tenant_id = :tenantId
                          AND membership.actor_id IN (:actorIds)
                        """)
                .param("tenantId", tenantId.value())
                .param("actorIds", requiredActorIds.stream().map(ActorId::value).toList())
                .query((resultSet, _) -> new ActorId(
                        resultSet.getObject("actor_id", UUID.class)
                ))
                .list());
    }
    public Set<ActorId> existingGroupMembers(
            TenantId tenantId,
            GroupId groupId,
            Collection<ActorId> actorIds
    ) {
        Set<ActorId> requiredActorIds = Set.copyOf(actorIds);
        if (requiredActorIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("""
                        SELECT membership.actor_id
                        FROM iam_group_memberships membership
                        WHERE membership.tenant_id = :tenantId
                          AND membership.group_id = :groupId
                          AND membership.actor_id IN (:actorIds)
                        """)
                .param("tenantId", tenantId.value())
                .param("groupId", groupId.value())
                .param("actorIds", requiredActorIds.stream().map(ActorId::value).toList())
                .query((resultSet, _) -> new ActorId(
                        resultSet.getObject("actor_id", UUID.class)
                ))
                .list());
    }

    public Set<GroupId> existingGroupMemberships(
            TenantId tenantId,
            ActorId actorId,
            Collection<GroupId> groupIds
    ) {
        Set<GroupId> requiredGroupIds = Set.copyOf(groupIds);
        if (requiredGroupIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbcClient.sql("""
                        SELECT membership.group_id
                        FROM iam_group_memberships membership
                        WHERE membership.tenant_id = :tenantId
                          AND membership.actor_id = :actorId
                          AND membership.group_id IN (:groupIds)
                        """)
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .param("groupIds", requiredGroupIds.stream().map(GroupId::value).toList())
                .query((resultSet, _) -> new GroupId(
                        resultSet.getObject("group_id", UUID.class)
                ))
                .list());
    }


    public Set<GroupId> existingGroups(TenantId tenantId, Collection<GroupId> groupIds) {
        return existingGroups(tenantId, groupIds, false);
    }

    public Set<GroupId> existingOrdinaryGroups(TenantId tenantId, Collection<GroupId> groupIds) {
        return existingGroups(tenantId, groupIds, true);
    }

    public boolean isManagedBy(TenantId tenantId, ActorId actorId, GroupId groupId) {
        return jdbcClient.sql(MANAGED_GROUP)
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .param("groupId", groupId.value())
                .query(Boolean.class)
                .single();
    }

    public AdminState adminState(TenantId tenantId, ActorId actorId) {
        return jdbcClient.sql(ADMIN_STATE)
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .query(GroupInvariantRepository::adminState)
                .single();
    }

    private Set<GroupId> existingGroups(
            TenantId tenantId,
            Collection<GroupId> groupIds,
            boolean ordinaryOnly
    ) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Set<GroupId> requiredGroupIds = Set.copyOf(groupIds);
        if (requiredGroupIds.isEmpty()) {
            return Set.of();
        }
        String ordinaryFilter = ordinaryOnly ? " AND group_record.system_key IS NULL" : "";
        return Set.copyOf(jdbcClient.sql("""
                        SELECT group_record.id
                        FROM iam_groups group_record
                        WHERE group_record.tenant_id = :tenantId
                          AND group_record.id IN (:groupIds)
                        """ + ordinaryFilter)
                .param("tenantId", tenantId.value())
                .param("groupIds", requiredGroupIds.stream().map(GroupId::value).toList())
                .query((resultSet, _) -> new GroupId(
                        resultSet.getObject("id", UUID.class)
                ))
                .list());
    }

    private static AdminState adminState(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AdminState(
                resultSet.getBoolean("configured_owner"),
                resultSet.getBoolean("active_standard_admin"),
                resultSet.getLong("active_standard_admin_count")
        );
    }

    public record AdminState(
            boolean configuredOwner,
            boolean activeStandardAdmin,
            long activeStandardAdminCount
    ) {
    }
}
