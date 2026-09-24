package io.memoryos.usage;

import static org.junit.jupiter.api.Assertions.*;

import com.zaxxer.hikari.HikariDataSource;
import io.memoryos.TestDatabase;
import io.memoryos.usage.persistence.UsageReportRepository;
import io.memoryos.usage.report.UsageReportStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class UsageReportRepositoryTest {
    private static final int MAX = 3;
    private HikariDataSource dataSource;
    private JdbcClient jdbc;
    private UsageReportRepository reports;
    private UUID tenant, manager;
    private final LocalDate day = LocalDate.parse("2026-09-18");

    @BeforeEach void setup() throws Exception {
        dataSource = TestDatabase.freshPostgres();
        jdbc = JdbcClient.create(dataSource);
        reports = new UsageReportRepository(jdbc);
        jdbc.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        tenant = tenant();
        manager = actor("Trần Thu Hà", "ha@tasco.vn");
    }

    @AfterEach void close() { if (dataSource != null) dataSource.close(); }

    @Test void aClaimLeasesTheOldestPendingReportOnce() {
        var first = reports.insert(tenant, UUID.randomUUID(), manager, day, day);
        reports.insert(tenant, UUID.randomUUID(), manager, day, day.plusDays(1));
        assertEquals("Trần Thu Hà", first.requester());
        var claim = reports.claim(Duration.ofMinutes(10), MAX).orElseThrow();
        assertEquals(first.id(), claim.id());
        assertEquals(1, claim.attempts());
        assertNotEquals(first.id(), reports.claim(Duration.ofMinutes(10), MAX).orElseThrow().id(), "a leased report is not claimed twice");
        assertTrue(reports.claim(Duration.ofMinutes(10), MAX).isEmpty());
    }

    @Test void aFailedAttemptRequeuesUntilTheLastOne() {
        var report = reports.insert(tenant, UUID.randomUUID(), manager, day, day);
        for (int attempt = 1; attempt <= MAX; attempt++) {
            var claim = reports.claim(Duration.ofMinutes(10), MAX).orElseThrow();
            assertEquals(attempt, claim.attempts());
            reports.markFailed(tenant, report.id(), claim.attempts(), MAX, "The report could not be generated.");
        }
        var failed = reports.find(tenant, report.id()).orElseThrow();
        assertEquals(UsageReportStatus.FAILED, failed.status());
        assertNotNull(failed.failure());
        assertTrue(reports.claim(Duration.ofMinutes(10), MAX).isEmpty());
    }

    @Test void aLapsedLeaseIsReclaimedAndTheStaleWorkerCannotFinish() {
        var report = reports.insert(tenant, UUID.randomUUID(), manager, day, day);
        var stale = reports.claim(Duration.ofMinutes(10), MAX).orElseThrow();
        jdbc.sql("UPDATE ai_usage_report SET lease_until = now() - interval '1 minute' WHERE id = :id").param("id", report.id()).update();
        var fresh = reports.claim(Duration.ofMinutes(10), MAX).orElseThrow();
        assertEquals(2, fresh.attempts());
        UUID object = storedObject();
        assertFalse(reports.markReady(tenant, report.id(), stale.attempts(), object, "raw/key", 10, true), "the stale Worker lost it");
        assertTrue(reports.markReady(tenant, report.id(), fresh.attempts(), object, "raw/key", 10, true));
        assertEquals(UsageReportStatus.READY, reports.find(tenant, report.id()).orElseThrow().status());
    }

    @Test void aLeaseThatLapsesOnTheLastAttemptFailsTheReport() {
        var report = reports.insert(tenant, UUID.randomUUID(), manager, day, day);
        jdbc.sql("UPDATE ai_usage_report SET status = 'RUNNING', attempts = :max, lease_until = now() - interval '1 minute' WHERE id = :id")
                .param("max", MAX).param("id", report.id()).update();
        assertEquals(1, reports.failAbandoned(MAX));
        assertEquals(UsageReportStatus.FAILED, reports.find(tenant, report.id()).orElseThrow().status());
    }

    @Test void reportsStayInsideTheirTenant() {
        var report = reports.insert(tenant, UUID.randomUUID(), manager, day, day);
        UUID other = tenant();
        assertTrue(reports.find(other, report.id()).isEmpty());
        assertTrue(reports.list(other, 50).isEmpty());
        assertEquals(1, reports.list(tenant, 50).size());
    }

    private UUID storedObject() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO stored_objects(tenant_id, id, object_key, filename, declared_media_type, size_bytes, content_sha256, state,
                    expires_at)
                VALUES (:tenant, :id, :key, 'usage.zip', 'application/zip', 10, :sha, 'ACTIVE', :expires)
                """).param("tenant", tenant).param("id", id).param("key", "raw/" + tenant + "/" + id)
                .param("sha", "0".repeat(64)).param("expires", java.sql.Timestamp.from(Instant.now().plusSeconds(3600))).update();
        return id;
    }

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO tenants(id, slug, display_name, status, bootstrap_reference) VALUES (:id, :slug, 'Tasco', 'ACTIVE', 'test')")
                .param("id", id).param("slug", id.toString()).update();
        return id;
    }

    private UUID actor(String name, String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO actors(id) VALUES (:id)").param("id", id).update();
        jdbc.sql("INSERT INTO external_identity_bindings(issuer, subject, actor_id) VALUES ('https://id.test', :subject, :id)")
                .param("subject", id.toString()).param("id", id).update();
        jdbc.sql("""
                INSERT INTO actor_profiles(actor_id, issuer, subject, display_name, email, email_verified, observed_at)
                VALUES (:id, 'https://id.test', :subject, :name, :email, true, :at)""")
                .param("id", id).param("subject", id.toString()).param("name", name).param("email", email)
                .param("at", java.sql.Timestamp.from(Instant.now())).update();
        return id;
    }
}
