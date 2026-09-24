package io.memoryos.audit;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.audit.persistence.JdbcAuditEventRepository;
import io.memoryos.shared.ActorId;
import io.memoryos.shared.TenantId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class AuditTrailTest {
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private SimpleMeterRegistry meters;
    private AuditTrail trail;
    private TenantId tenant;
    private ActorId manager;

    @BeforeEach void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        var transactions = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(transactions);
        meters = new SimpleMeterRegistry();
        trail = new AuditTrail(new JdbcAuditEventRepository(jdbc), AuditRequestContext.TRACE_ONLY, meters, transactions);
        tenant = tenant();
        manager = actor("Trần Thu Hà");
    }

    @AfterEach void close() { if (dataSource != null) dataSource.close(); }

    @Test void anEventBelongsToTheTransactionOfTheChangeItRecords() {
        tx.executeWithoutResult(ignored -> {
            jdbc.sql("INSERT INTO iam_groups(tenant_id, id, name) VALUES (:t, :id, 'Kế toán')")
                    .param("t", tenant.value()).param("id", UUID.randomUUID()).update();
            trail.record(AuditRecord.of(AuditAction.GROUP_CREATE, tenant).actor(manager, "Trần Thu Hà").build());
        });
        assertEquals(1, events());

        // A change that rolls back leaves no evidence of having happened.
        assertThrows(RuntimeException.class, () -> tx.executeWithoutResult(ignored -> {
            trail.record(AuditRecord.of(AuditAction.GROUP_DELETE, tenant).actor(manager, "Trần Thu Hà").build());
            throw new IllegalStateException("the administrator's change failed");
        }));
        assertEquals(1, events());
    }

    @Test void aFailureToRecordLeavesTheChangeCommitted() {
        UUID group = UUID.randomUUID();
        // An action longer than the column: the insert fails, the Group is still created.
        jdbc.sql("ALTER TABLE audit_event ALTER COLUMN action TYPE varchar(4)").update();
        tx.executeWithoutResult(ignored -> {
            jdbc.sql("INSERT INTO iam_groups(tenant_id, id, name) VALUES (:t, :id, 'Pháp chế')")
                    .param("t", tenant.value()).param("id", group).update();
            trail.record(AuditRecord.of(AuditAction.GROUP_CREATE, tenant).actor(manager, "Trần Thu Hà")
                    .resource("GROUP", group, "Pháp chế").build());
        });
        assertEquals(1, (long) jdbc.sql("SELECT count(*) FROM iam_groups WHERE id = :id").param("id", group)
                .query(Long.class).single());
        assertEquals(0, events(), "the event could not be stored");
        assertEquals(1.0, meters.counter("memoryos.audit.write.failures", "action", "user_group.create").count());
    }

    @Test void anEventKeepsWhoAndWhatWereCalledAtTheTime() {
        UUID group = UUID.randomUUID();
        tx.executeWithoutResult(ignored -> trail.record(AuditRecord.of(AuditAction.GROUP_RENAME, tenant)
                .actor(manager, "Trần Thu Hà").resource("GROUP", group, "Kế toán")
                .detail("before", "Kế toán").detail("after", "Tài chính").build()));
        var row = jdbc.sql("SELECT * FROM audit_event").query().singleRow();
        assertEquals("user_group.rename", row.get("action"));
        assertEquals("GROUP_MANAGEMENT", row.get("event_class"));
        assertEquals("SUCCESS", row.get("outcome"));
        assertEquals("Trần Thu Hà", row.get("actor_label"));
        assertEquals(group.toString(), row.get("resource_id"));
        assertEquals("Kế toán", row.get("resource_label"));
        assertTrue(String.valueOf(row.get("details")).contains("Tài chính"));
    }

    @Test void anActionOnlyCarriesTheDetailsItDeclares() {
        var event = AuditRecord.of(AuditAction.PROVIDER_UPDATE, tenant);
        assertThrows(IllegalArgumentException.class, () -> event.detail("apiKey", "sk-live-should-never-be-recorded"));
        assertDoesNotThrow(() -> event.detail("credentialChange", "REPLACED"));
    }

    @Test void nothingRewritesOrRemovesAnEventOutsideTheRetentionSweep() {
        tx.executeWithoutResult(ignored -> trail.record(AuditRecord.of(AuditAction.USER_DEACTIVATE, tenant)
                .actor(manager, "Trần Thu Hà").detail("email", "quan@tasco.vn").build()));
        assertThrows(RuntimeException.class, () -> jdbc.sql("UPDATE audit_event SET outcome = 'DENIED'").update());
        assertThrows(RuntimeException.class, () -> jdbc.sql("DELETE FROM audit_event").update());
        assertEquals(1, events());
        tx.executeWithoutResult(ignored -> {
            jdbc.sql("SELECT set_config('memoryos.audit_retention', 'on', true)").query().singleValue();
            jdbc.sql("DELETE FROM audit_event").update();
        });
        assertEquals(0, events());
    }

    @Test void aRefusalSurvivesTheRollbackThatRefusesIt() {
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(ignored -> {
            trail.recordSeparately(AuditRecord.of(AuditAction.PERMISSION_DENIED, tenant).outcome(AuditOutcome.DENIED)
                    .actor(manager).detail("capability", "GROUPS_MANAGE").detail("scope", "GROUP").build());
            throw new IllegalStateException("denied");
        }));
        assertEquals(1, events());
        assertEquals("ha@tasco.vn", jdbc.sql("SELECT actor_email FROM audit_event").query(String.class).single());
    }

    @Test void everyActionIsReadableBackAndUniquelyNamed() {
        for (AuditAction action : AuditAction.values()) {
            assertEquals(action, AuditAction.of(action.value()).orElseThrow(), action.name());
            assertNotNull(action.eventClass(), action.name());
        }
        assertTrue(AuditAction.of("something.removed").isEmpty(), "an unknown stored action does not fail a reader");
    }

    private long events() {
        return jdbc.sql("SELECT count(*) FROM audit_event WHERE tenant_id = :t").param("t", tenant.value())
                .query(Long.class).single();
    }

    private TenantId tenant() {
        UUID id = UUID.randomUUID();
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT IF EXISTS uq_tenants_deployment_slot").update();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Tasco', 'ACTIVE', 'test')")
                .param("id", id).param("slug", id.toString()).update();
        return new TenantId(id);
    }

    private ActorId actor(String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", id).update();
        jdbc.sql("INSERT INTO external_identity_bindings(issuer, subject, actor_id) VALUES ('https://id.test', :subject, :id)")
                .param("subject", id.toString()).param("id", id).update();
        jdbc.sql("""
                INSERT INTO actor_profiles(actor_id, issuer, subject, display_name, email, email_verified, observed_at)
                VALUES (:id, 'https://id.test', :subject, :name, :email, true, :at)""")
                .param("id", id).param("subject", id.toString()).param("name", name).param("email", "ha@tasco.vn")
                .param("at", java.sql.Timestamp.from(Instant.now())).update();
        return new ActorId(id);
    }

}
