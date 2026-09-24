package io.memoryos.audit;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.audit.persistence.JdbcAuditEventRepository;
import io.memoryos.audit.persistence.JdbcAuditLogQueryRepository;
import io.memoryos.iam.IamException;
import io.memoryos.iam.IamFailureReason;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class AuditLogTest {
    private static final AuditLog.Query ALL = new AuditLog.Query(null, null, null, null, null, null, null, null, null);
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate tx;
    private AuditTrail trail;
    private AuditLog log;
    private UUID tenant, other;
    private UUID reader, stranger;

    @BeforeEach void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        var transactions = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(transactions);
        trail = TestDatabase.audit(jdbc, transactions);
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT IF EXISTS uq_tenants_deployment_slot").update();
        tenant = tenant();
        other = tenant();
        reader = UUID.randomUUID();
        stranger = UUID.randomUUID();
        // IAM answers who may read; here the reader may and anyone else is refused as IAM refuses them.
        AuditReaders readers = actor -> {
            if (actor.equals(reader)) return tenant;
            throw new IamException(IamFailureReason.ACCESS_DENIED, "no audit read");
        };
        log = TestDatabase.transactionalProxy(new AuditLog(new JdbcAuditLogQueryRepository(jdbc), readers, trail), AuditLog.class, transactions);
    }

    @AfterEach void close() { if (dataSource != null) dataSource.close(); }

    @Test void pagesAreStableWhileNewEventsArrive() {
        for (int i = 0; i < 7; i++) record(tenant, AuditAction.GROUP_CREATE, "Nhóm " + i);
        var first = log.page(reader, ALL, null, 3);
        assertEquals(3, first.items().size());
        assertNotNull(first.nextCursor());
        // An event recorded between pages lands before the cursor and neither shifts nor repeats a later page.
        record(tenant, AuditAction.GROUP_CREATE, "Nhóm mới");
        var seen = new ArrayList<UUID>();
        first.items().forEach(event -> seen.add(event.id()));
        String cursor = first.nextCursor();
        while (cursor != null) {
            var page = log.page(reader, ALL, cursor, 3);
            page.items().forEach(event -> seen.add(event.id()));
            cursor = page.nextCursor();
        }
        assertEquals(7, seen.size());
        assertEquals(7, seen.stream().distinct().count());
    }

    @Test void filtersNarrowTheStreamAndStayInsideTheTenant() {
        record(tenant, AuditAction.GROUP_CREATE, "Kế toán");
        record(tenant, AuditAction.USER_DEACTIVATE, "Nguyễn Đức Quân");
        record(other, AuditAction.GROUP_CREATE, "Tenant khác");
        assertEquals(2, log.page(reader, ALL, null, 50).items().size(), "another Tenant's events are not read");
        var groups = new AuditLog.Query(null, null, null, AuditEventClass.GROUP_MANAGEMENT, null, null, null, null, null);
        assertEquals("Kế toán", log.page(reader, groups, null, 50).items().getFirst().resourceLabel());
        var byText = new AuditLog.Query(null, null, "quân", null, null, null, null, null, null);
        assertEquals("user.deactivate", log.page(reader, byText, null, 50).items().getFirst().action());
        var literal = new AuditLog.Query(null, null, "%", null, null, null, null, null, null);
        assertTrue(log.page(reader, literal, null, 50).items().isEmpty(), "a percent sign is searched for, not a wildcard");
        var future = new AuditLog.Query(Instant.now().plusSeconds(60), null, null, null, null, null, null, null, null);
        assertTrue(log.page(reader, future, null, 50).items().isEmpty());
        assertThrows(AuditException.class, () -> new AuditLog.Query(null, null, null, null, "no.such_action", null, null, null, null));
        assertThrows(AuditException.class, () -> log.page(reader, ALL, "not-a-cursor", 50));
    }

    @Test void readingNeedsAuditReadAndExportingIsRecorded() {
        record(tenant, AuditAction.GROUP_CREATE, "Kế toán");
        record(tenant, AuditAction.GROUP_RENAME, "Tài chính");
        assertThrows(IamException.class, () -> log.page(stranger, ALL, null, 50));
        assertThrows(IamException.class, () -> log.export(stranger, ALL, event -> { }));
        var exported = new ArrayList<AuditLog.Event>();
        assertEquals(2, log.export(reader, ALL, exported::add));
        assertEquals(2, exported.size());
        var newest = log.page(reader, ALL, null, 1).items().getFirst();
        assertEquals("audit.export", newest.action());
        assertEquals(2, ((Number) newest.details().get("rows")).intValue());
    }

    @Test void retentionDeletesOnlyExpiredEvents() {
        record(tenant, AuditAction.GROUP_CREATE, "Cũ");
        record(tenant, AuditAction.GROUP_CREATE, "Mới");
        tx.executeWithoutResult(ignored -> {
            jdbc.sql("SELECT set_config('memoryos.audit_retention', 'on', true)").query().singleValue();
            jdbc.sql("DELETE FROM audit_event WHERE resource_label = 'Cũ'").update();
            jdbc.sql("""
                    INSERT INTO audit_event(id, tenant_id, occurred_at, action, event_class, outcome, resource_label)
                    VALUES (:id, :tenant, :at, 'user_group.create', 'GROUP_MANAGEMENT', 'SUCCESS', 'Cũ')
                    """).param("id", UUID.randomUUID()).param("tenant", tenant)
                    .param("at", java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(400)))).update();
        });
        var retention = TestDatabase.transactionalProxy(new AuditRetention(new JdbcAuditEventRepository(jdbc), Duration.ofDays(365)), AuditRetention.class,
                new DataSourceTransactionManager(dataSource));
        assertEquals(1, retention.sweep());
        assertEquals(0, retention.sweep());
        assertEquals("Mới", log.page(reader, ALL, null, 50).items().getFirst().resourceLabel());
        // Outside the sweep the stream still refuses deletion.
        assertThrows(RuntimeException.class, () -> jdbc.sql("DELETE FROM audit_event").update());
    }

    private void record(UUID in, AuditAction action, String label) {
        tx.executeWithoutResult(ignored -> trail.record(AuditRecord.of(action, in).actor(null, "Trần Thu Hà")
                .resource(action == AuditAction.USER_DEACTIVATE ? "USER" : "GROUP", UUID.randomUUID(), label).build()));
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Tasco', 'ACTIVE', 'test')")
                .param("id", id).param("slug", id.toString()).update();
        return id;
    }
}
