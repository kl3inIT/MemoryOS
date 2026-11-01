package io.memoryos.connector.source.persistence;

import io.memoryos.connector.SourceAccessChanged;
import io.memoryos.connector.SourceId;
import io.memoryos.iam.GroupId;
import io.memoryos.iam.GroupIdentity;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcSourceGroupRepository {

    private final JdbcClient jdbcClient;
    private final ApplicationEventPublisher events;

    public JdbcSourceGroupRepository(JdbcClient jdbcClient, ApplicationEventPublisher events) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    /** The Source's current ordinary Group associations, used to authorize an association change per Group. */
    public Set<GroupId> groupIds(TenantId tenantId, SourceId sourceId) {
        return Set.copyOf(jdbcClient.sql("""
                        SELECT source_grant.group_id
                        FROM source_group_grants source_grant
                        JOIN iam_groups iam_group
                          ON iam_group.tenant_id = source_grant.tenant_id
                         AND iam_group.id = source_grant.group_id
                        WHERE source_grant.tenant_id = :tenantId
                          AND source_grant.connector_credential_pair_id = :sourceId
                          AND iam_group.system_key IS NULL
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .query((resultSet, ignored) -> new GroupId(resultSet.getObject("group_id", UUID.class)))
                .list());
    }

    /**
     * Sources in this Group the caller may detach from it: with global access every associated Source, otherwise only
     * those the caller manages under the scoped write rule. The caller has already been authorized for the Group. A
     * Source being deleted is excluded because its associations are being torn down.
     */
    public Set<SourceId> removableFromGroup(TenantId tenantId, ActorId actorId, GroupId groupId, boolean globalAccess) {
        return Set.copyOf(jdbcClient.sql("""
                        SELECT pair.id
                        FROM source_group_grants requested_grant
                        JOIN connector_credential_pairs pair
                          ON pair.tenant_id = requested_grant.tenant_id
                         AND pair.id = requested_grant.connector_credential_pair_id
                        WHERE requested_grant.tenant_id = :tenantId
                          AND requested_grant.group_id = :groupId
                          AND pair.status <> 'DELETING'
                          AND (:globalAccess OR %s)
                        """.formatted(SourceScopeSql.WRITE))
                .param("tenantId", tenantId.value())
                .param("actorId", actorId.value())
                .param("groupId", groupId.value())
                .param("globalAccess", globalAccess)
                .query((resultSet, ignored) -> new SourceId(resultSet.getObject("id", UUID.class)))
                .list());
    }

    public void remove(TenantId tenantId, SourceId sourceId, GroupId groupId) {
        int removed = jdbcClient.sql("""
                        DELETE FROM source_group_grants
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :sourceId
                          AND group_id = :groupId
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .param("groupId", groupId.value())
                .update();
        if (removed != 1) {
            throw new IllegalStateException("Source association changed while the Source lock was held");
        }
        // Listeners run in this transaction; a rollback also discards their index work.
        events.publishEvent(new SourceAccessChanged(tenantId, sourceId));
    }

    public List<GroupIdentity> list(TenantId tenantId, SourceId sourceId) {
        return jdbcClient.sql("""
                        SELECT iam_group.id, iam_group.name
                        FROM source_group_grants source_grant
                        JOIN iam_groups iam_group
                          ON iam_group.tenant_id = source_grant.tenant_id
                         AND iam_group.id = source_grant.group_id
                        WHERE source_grant.tenant_id = :tenantId
                          AND source_grant.connector_credential_pair_id = :sourceId
                          AND iam_group.system_key IS NULL
                        ORDER BY LOWER(iam_group.name), iam_group.id
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .query((resultSet, ignored) -> new GroupIdentity(
                        new GroupId(resultSet.getObject("id", UUID.class)),
                        resultSet.getString("name"),
                        null
                ))
                .list();
    }

    public void replace(TenantId tenantId, SourceId sourceId, Collection<GroupId> groupIds) {
        Objects.requireNonNull(groupIds, "groupIds must not be null");
        List<UUID> values = groupIds.stream()
                .map(groupId -> Objects.requireNonNull(groupId, "groupId must not be null").value())
                .toList();
        jdbcClient.sql("""
                        DELETE FROM source_group_grants
                        WHERE tenant_id = :tenantId
                          AND connector_credential_pair_id = :sourceId
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .update();
        // Listeners run in this transaction; a rollback also discards their index work.
        events.publishEvent(new SourceAccessChanged(tenantId, sourceId));
        if (values.isEmpty()) {
            return;
        }
        int inserted = jdbcClient.sql("""
                        INSERT INTO source_group_grants (
                            tenant_id, connector_credential_pair_id, group_id
                        )
                        SELECT :tenantId, :sourceId, iam_group.id
                        FROM iam_groups iam_group
                        WHERE iam_group.tenant_id = :tenantId
                          AND iam_group.system_key IS NULL
                          AND iam_group.id IN (:groupIds)
                        """)
                .param("tenantId", tenantId.value())
                .param("sourceId", sourceId.value())
                .param("groupIds", values)
                .update();
        if (inserted != values.size()) {
            throw new IllegalStateException("Validated source groups changed while the Tenant lock was held");
        }
    }
}
