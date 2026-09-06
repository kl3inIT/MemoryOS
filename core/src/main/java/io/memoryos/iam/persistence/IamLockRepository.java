package io.memoryos.iam.persistence;

import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import io.memoryos.iam.TenantId;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shared Tenant serialization anchor. Exclusive acquisition also advances the client
 * authorization revision in the same transaction; shared acquisition never does.
 */
@Repository
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class IamLockRepository {
    private static final String LOCK_TENANT_EXCLUSIVE = """
            SELECT id
            FROM tenants
            WHERE id = :tenantId
            FOR UPDATE
            """;
    private static final String LOCK_TENANT_SHARED = """
            SELECT id
            FROM tenants
            WHERE id = :tenantId
            FOR SHARE
            """;
    private static final String BUMP_AUTHORIZATION_VERSION = """
            UPDATE tenants
            SET authorization_version = authorization_version + 1
            WHERE id = :tenantId
            """;


    private final JdbcClient jdbcClient;

    public IamLockRepository(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTenant(TenantId tenantId) {
        TenantId requiredTenantId = requireTenantId(tenantId);
        lock(requiredTenantId, LOCK_TENANT_EXCLUSIVE);
        int updated = jdbcClient.sql(BUMP_AUTHORIZATION_VERSION)
                .param("tenantId", requiredTenantId.value())
                .update();
        if (updated != 1) {
            throw new IamException(
                    IamFailureReason.ACCESS_DENIED,
                    "Tenant authorization revision could not be advanced"
            );
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockTenantShared(TenantId tenantId) {
        lock(requireTenantId(tenantId), LOCK_TENANT_SHARED);
    }

    private void lock(TenantId tenantId, String statement) {
        if (jdbcClient.sql(statement)
                .param("tenantId", tenantId.value())
                .query(UUID.class)
                .optional()
                .isEmpty()) {
            throw new IamException(IamFailureReason.ACCESS_DENIED, "Tenant authorization anchor is absent");
        }
    }

    private static TenantId requireTenantId(TenantId tenantId) {
        return Objects.requireNonNull(tenantId, "tenantId must not be null");
    }
}
