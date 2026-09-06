package io.memoryos.iam.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.TestDatabase;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.IamException;
import io.memoryos.iam.TenantId;
import io.memoryos.iam.persistence.GroupEntity;
import io.memoryos.iam.persistence.GroupInvariantRepository;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresGroupAdministrationGuardTest {
    private static final TenantId TENANT = new TenantId(
            UUID.fromString("10000000-0000-0000-0000-000000000056")
    );
    private static final ActorId OWNER = new ActorId(
            UUID.fromString("20000000-0000-0000-0000-000000000056")
    );
    private static final ActorId SECOND_ADMIN = new ActorId(
            UUID.fromString("30000000-0000-0000-0000-000000000056")
    );

    private JdbcClient jdbc;
    private TransactionTemplate transaction;
    private DefaultGroupAdministrationGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        guard = new DefaultGroupAdministrationGuard(new GroupInvariantRepository(jdbc));
        jdbc.sql("""
                        INSERT INTO tenants (
                            id, slug, display_name, status, bootstrap_reference, deployment_slot
                        ) VALUES (:id, 'admin-guard-test', 'Admin guard test', 'ACTIVE', 'test', 1)
                        """)
                .param("id", TENANT.value())
                .update();
        persistActor(OWNER, "OWNER");
        jdbc.sql("""
                        INSERT INTO iam_groups (tenant_id, id, name, system_key)
                        VALUES (:tenantId, :groupId, 'Admin', 'ADMIN')
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", GroupEntity.ADMIN_ID)
                .update();
        addAdminMembership(OWNER);
    }

    @Test
    void configuredOwnerAndFinalActiveStandardAdministratorSurvive() {
        IamException ownerFailure = assertThrows(
                IamException.class,
                () -> transaction.executeWithoutResult(_ -> guard.requireCanDeactivate(TENANT, OWNER))
        );
        assertEquals("IAM_CONFIGURED_OWNER_PROTECTED", ownerFailure.code());

        jdbc.sql("""
                        UPDATE tenant_memberships
                        SET role = 'MEMBER'
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", TENANT.value())
                .param("actorId", OWNER.value())
                .update();
        IamException finalAdminFailure = assertThrows(
                IamException.class,
                () -> transaction.executeWithoutResult(_ -> guard.requireCanDeactivate(TENANT, OWNER))
        );
        assertEquals("IAM_LAST_ADMIN_PROTECTED", finalAdminFailure.code());

        persistActor(SECOND_ADMIN, "MEMBER");
        addAdminMembership(SECOND_ADMIN);
        assertDoesNotThrow(() -> transaction.executeWithoutResult(
                _ -> guard.requireCanDeactivate(TENANT, OWNER)
        ));
    }

    private void persistActor(ActorId actorId, String role) {
        jdbc.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", actorId.value())
                .update();
        jdbc.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, :role, 'ACTIVE')
                        """)
                .param("tenantId", TENANT.value())
                .param("actorId", actorId.value())
                .param("role", role)
                .update();
    }

    private void addAdminMembership(ActorId actorId) {
        jdbc.sql("""
                        INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id)
                        VALUES (:tenantId, :groupId, :actorId)
                        """)
                .param("tenantId", TENANT.value())
                .param("groupId", GroupEntity.ADMIN_ID)
                .param("actorId", actorId.value())
                .update();
    }
}
