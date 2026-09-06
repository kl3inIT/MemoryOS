package io.memoryos.iam.persistence;

import io.memoryos.iam.ActorId;
import io.memoryos.iam.IamCapability;
import io.memoryos.iam.TenantId;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class IamAuthorizationRepository {
    private static final String FIND_AUTHORITY = """
            SELECT DISTINCT
                membership.tenant_id,
                tenant.authorization_version,
                capability.capability,
                EXISTS (
                    SELECT 1
                    FROM iam_group_memberships managed_membership
                    JOIN iam_groups managed_group
                      ON managed_group.tenant_id = managed_membership.tenant_id
                     AND managed_group.id = managed_membership.group_id
                    WHERE managed_membership.tenant_id = membership.tenant_id
                      AND managed_membership.actor_id = membership.actor_id
                      AND managed_membership.is_manager
                      AND managed_group.system_key IS NULL
                ) AS manages_ordinary_group
            FROM tenant_memberships membership
            JOIN tenants tenant
              ON tenant.id = membership.tenant_id
             AND tenant.status = 'ACTIVE'
            JOIN actors actor
              ON actor.id = membership.actor_id
             AND actor.account_type = 'STANDARD'
            LEFT JOIN iam_group_memberships group_membership
              ON group_membership.tenant_id = membership.tenant_id
             AND group_membership.actor_id = membership.actor_id
            LEFT JOIN iam_groups authority_group
              ON authority_group.tenant_id = group_membership.tenant_id
             AND authority_group.id = group_membership.group_id
            LEFT JOIN iam_group_capability_grants capability
              ON capability.tenant_id = authority_group.tenant_id
             AND capability.group_id = authority_group.id
             AND (
                    (authority_group.system_key = 'ADMIN' AND capability.capability = 'IAM_ADMIN')
                    OR (
                        authority_group.system_key IS NULL
                        AND capability.capability <> 'IAM_ADMIN'
                    )
             )
            WHERE membership.actor_id = :actorId
              AND membership.status = 'ACTIVE'
            ORDER BY membership.tenant_id, capability.capability
            """;

    private final JdbcClient jdbcClient;

    public IamAuthorizationRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    public Optional<AuthorizationSnapshot> find(ActorId actorId) {
        ActorId requiredActorId = Objects.requireNonNull(actorId, "actorId must not be null");
        List<AuthorityRow> rows = jdbcClient.sql(FIND_AUTHORITY)
                .param("actorId", requiredActorId.value())
                .query(IamAuthorizationRepository::row)
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        UUID tenantId = rows.getFirst().tenantId();
        long authorizationVersion = rows.getFirst().authorizationVersion();
        EnumSet<IamCapability> explicitCapabilities = EnumSet.noneOf(IamCapability.class);
        boolean managesOrdinaryGroup = false;
        for (AuthorityRow row : rows) {
            if (!tenantId.equals(row.tenantId())
                    || authorizationVersion != row.authorizationVersion()) {
                return Optional.empty();
            }
            managesOrdinaryGroup |= row.managesOrdinaryGroup();
            if (row.capability() != null) {
                try {
                    explicitCapabilities.add(IamCapability.valueOf(row.capability()));
                } catch (IllegalArgumentException unknownCapability) {
                    return Optional.empty();
                }
            }
        }
        Set<IamCapability> immutableCapabilities = explicitCapabilities.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(explicitCapabilities));
        return Optional.of(new AuthorizationSnapshot(
                new TenantId(tenantId),
                immutableCapabilities,
                managesOrdinaryGroup,
                authorizationVersion
        ));
    }

    private static AuthorityRow row(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuthorityRow(
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getLong("authorization_version"),
                resultSet.getString("capability"),
                resultSet.getBoolean("manages_ordinary_group")
        );
    }

    public record AuthorizationSnapshot(
            TenantId tenantId,
            Set<IamCapability> explicitCapabilities,
            boolean managesOrdinaryGroup,
            long authorizationVersion
    ) {
        public AuthorizationSnapshot {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            explicitCapabilities = Set.copyOf(explicitCapabilities);
        }
    }

    private record AuthorityRow(
            UUID tenantId,
            long authorizationVersion,
            @Nullable String capability,
            boolean managesOrdinaryGroup
    ) {
    }
}
