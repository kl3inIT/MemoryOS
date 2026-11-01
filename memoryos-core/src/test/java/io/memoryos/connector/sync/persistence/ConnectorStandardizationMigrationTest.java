package io.memoryos.connector.sync.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.TestDatabase;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * V134 moves what every provider shares into provider-neutral tables. Every row a deployment holds survives, and
 * the database still ends a verification when its credential, Source, actor or Tenant loses authority.
 */
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class ConnectorStandardizationMigrationTest {
    private static final List<String> STATUSES =
            List.of("NOT_STARTED", "IN_PROGRESS", "SUCCEEDED", "FAILED", "SUPERSEDED", "CANCELLED");
    private static final String LIVE = "status IN ('NOT_STARTED','IN_PROGRESS')";

    private JdbcClient jdbc;
    private UUID tenant;
    private UUID actor;
    private UUID otherActor;
    private UUID googleCredential;
    private UUID sharePointCredential;
    private UUID driftedCredential;
    private UUID looseCredential;
    private UUID driveSource;
    private UUID sharePointSource;
    private final Map<String, UUID> googleOperations = new LinkedHashMap<>();
    private final Map<String, UUID> sharePointOperations = new LinkedHashMap<>();

    @Test
    void everySelectionRequestKeepsItsColumnsAndItsCheckpoint() throws Exception {
        try (var database = TestDatabase.freshPostgres("133")) {
            seed(database);
            var header = """
                    SELECT (to_jsonb(o) - 'scope_mode' - 'discovery_revision' - 'max_metadata' - 'ancestor_count'
                        - 'metadata_count' - 'include_documents' - 'include_pages' - 'sync_interval_minutes'
                        - 'prune_interval_hours' - 'source_type')::text
                    FROM %s o %s ORDER BY id
                    """;
            var googleBefore = rows(header.formatted("google_drive_selection_operations", ""));
            var sharePointBefore = rows(header.formatted("sharepoint_selection_operations", ""));
            var googleDetails = rows("""
                    SELECT jsonb_build_array(id, scope_mode, discovery_revision, max_metadata, ancestor_count,
                        metadata_count)::text FROM google_drive_selection_operations ORDER BY id
                    """);
            var sharePointDetails = rows("""
                    SELECT jsonb_build_array(id, scope_mode, include_documents, include_pages, sync_interval_minutes,
                        prune_interval_hours)::text FROM sharepoint_selection_operations ORDER BY id
                    """);

            migrate(database);

            assertEquals(googleBefore,
                    rows(header.formatted("source_selection_operations", "WHERE source_type = 'GOOGLE_DRIVE'")));
            assertEquals(sharePointBefore,
                    rows(header.formatted("source_selection_operations", "WHERE source_type = 'SHAREPOINT'")));
            assertEquals(googleDetails, rows("""
                    SELECT jsonb_build_array(operation_id, scope_mode, discovery_revision, max_metadata,
                        ancestor_count, metadata_count)::text FROM google_drive_selection_details ORDER BY operation_id
                    """));
            assertEquals(sharePointDetails, rows("""
                    SELECT jsonb_build_array(operation_id, scope_mode, include_documents, include_pages,
                        sync_interval_minutes, prune_interval_hours)::text
                    FROM sharepoint_selection_details ORDER BY operation_id
                    """));
            assertEquals(STATUSES.size(), count("google_drive_selection_entries"));
            assertEquals(STATUSES.size(), count("google_drive_selection_ancestors"));
            assertEquals(STATUSES.size(), count("google_drive_selection_metadata"));
            assertEquals(STATUSES.size(), count("sharepoint_selection_entries"));
            assertEquals(0, count("information_schema.tables WHERE table_name IN "
                    + "('google_drive_selection_operations','sharepoint_selection_operations')"));

            // A request ID stays an idempotency key within one provider.
            jdbc.sql("""
                    INSERT INTO source_selection_operations (id, tenant_id, source_id, source_type, actor_id, request_id,
                        request_hash, credential_revision, scope_revision, max_requests, max_roots, max_request_bytes,
                        status)
                    SELECT gen_random_uuid(), tenant_id, gen_random_uuid(), 'SHAREPOINT', actor_id, request_id, 'hash',
                        1, 1, 1, 1, 1, 'SUCCEEDED' FROM source_selection_operations WHERE id = :id
                    """).param("id", googleOperations.get("SUCCEEDED")).update();
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                    INSERT INTO source_selection_operations (id, tenant_id, source_id, source_type, actor_id, request_id,
                        request_hash, credential_revision, scope_revision, max_requests, max_roots, max_request_bytes,
                        status)
                    SELECT gen_random_uuid(), tenant_id, gen_random_uuid(), 'GOOGLE_DRIVE', actor_id, request_id, 'hash',
                        1, 1, 1, 1, 1, 'SUCCEEDED' FROM source_selection_operations WHERE id = :id
                    """).param("id", googleOperations.get("SUCCEEDED")).update());

            // A finished Google Drive verification drops its traversal checkpoint and keeps its entries.
            jdbc.sql("UPDATE source_selection_operations SET status = 'SUCCEEDED' WHERE id = :id")
                    .param("id", googleOperations.get("IN_PROGRESS")).update();
            assertEquals(0, count("google_drive_selection_ancestors WHERE operation_id = :id",
                    googleOperations.get("IN_PROGRESS")));
            assertEquals(0, count("google_drive_selection_metadata WHERE operation_id = :id",
                    googleOperations.get("IN_PROGRESS")));
            assertEquals(1, count("google_drive_selection_entries WHERE operation_id = :id",
                    googleOperations.get("IN_PROGRESS")));
            // Deleting a request removes its details and everything checkpointed under them.
            jdbc.sql("DELETE FROM source_selection_operations WHERE id IN (:google, :sharePoint)")
                    .param("google", googleOperations.get("FAILED"))
                    .param("sharePoint", sharePointOperations.get("FAILED")).update();
            assertEquals(STATUSES.size() - 1, count("google_drive_selection_details"));
            assertEquals(STATUSES.size() - 1, count("google_drive_selection_entries"));
            assertEquals(STATUSES.size() - 1, count("sharepoint_selection_details"));
            assertEquals(STATUSES.size() - 1, count("sharepoint_selection_entries"));
        }
    }

    @Test
    void synchronizationStateAndCredentialRevisionsMoveToTheSharedTables() throws Exception {
        try (var database = TestDatabase.freshPostgres("133")) {
            seed(database);

            migrate(database);

            assertEquals(List.of("[4, 6, 2, 45, true]", "[9, 3, 5, 120, false]"), rows("""
                    SELECT jsonb_build_array(scope_revision, generation, schedule_revision, sync_interval_minutes,
                        sync_paused)::text
                    FROM source_sync_state ORDER BY scope_revision
                    """));
            assertEquals(2, count("source_sync_state WHERE last_synced_at = TIMESTAMPTZ '2026-09-01 10:00:00+00' "
                    + "AND next_sync_at = TIMESTAMPTZ '2026-09-02 10:00:00+00'"));
            assertEquals("GENERAL", jdbc.sql("SELECT scope_mode FROM google_drive_sources").query(String.class).single());
            assertEquals("[\"ALL_SITES\", 72]", jdbc.sql("""
                    SELECT jsonb_build_array(scope_mode, prune_interval_hours)::text FROM sharepoint_sources
                    """).query(String.class).single());
            assertEquals(0, count("""
                    information_schema.columns WHERE table_name IN ('google_drive_sources', 'sharepoint_sources')
                      AND column_name IN ('revision', 'scope_revision', 'generation', 'schedule_revision',
                          'sync_interval_minutes', 'sync_paused', 'next_sync_at', 'last_synced_at')
                    """));
            // A provider Source row cannot exist without its synchronization state.
            UUID stateless = UUID.randomUUID();
            pair(stateless, "GOOGLE_DRIVE", googleCredential);
            assertTrue(assertThrows(DataIntegrityViolationException.class, () -> jdbc
                    .sql("INSERT INTO google_drive_sources (tenant_id, source_id) VALUES (:tenant, :source)")
                    .param("tenant", tenant).param("source", stateless).update())
                    .getMessage().contains("fk_google_drive_source_sync_state"));

            assertEquals(List.of("GOOGLE_OAUTH/ACTIVE/3/5", "NO_AUTH/ACTIVE/1/1", "SHAREPOINT_APP/ACTIVE/2/4",
                    "SHAREPOINT_APP/ACTIVE/6/6", "SHAREPOINT_APP/NEEDS_UPDATE/8/2"), rows("""
                    SELECT credential_kind || '/' || status || '/' || credential_revision || '/' || payload_revision
                    FROM credentials ORDER BY 1
                    """), "A provider copy of the status that drifted wins");
            assertEquals(0, count("""
                    information_schema.columns
                    WHERE table_name IN ('google_drive_credentials', 'sharepoint_credentials')
                      AND column_name IN ('credential_revision', 'payload_revision')
                      OR table_name = 'sharepoint_credentials' AND column_name = 'connection_status'
                    """));
            // Google Drive's envelope status cannot drift from the credential's status.
            assertThrows(DataIntegrityViolationException.class, () -> jdbc
                    .sql("UPDATE google_drive_credentials SET connection_status = 'NEEDS_REAUTHORIZATION'").update());
        }
    }

    @Test
    void lostAuthorityStillEndsTheVerificationsItWasAcceptedFor() throws Exception {
        try (var database = TestDatabase.freshPostgres("133")) {
            seed(database);
            migrate(database);
            UUID driveLive = pending("GOOGLE_DRIVE", UUID.randomUUID(), googleCredential, otherActor);
            UUID sharePointLive = pending("SHAREPOINT", UUID.randomUUID(), sharePointCredential, otherActor);
            UUID looseLive = pending("SHAREPOINT", UUID.randomUUID(), looseCredential, otherActor);
            UUID driftedLive = pending("SHAREPOINT", UUID.randomUUID(), driftedCredential, otherActor);

            jdbc.sql("UPDATE credentials SET credential_revision = credential_revision + 1 WHERE id = :id")
                    .param("id", googleCredential).update();
            assertEquals("CANCELLED/SOURCE_GOOGLE_CREDENTIAL_CHANGED", outcome(driveLive));
            assertEquals("CANCELLED/SOURCE_GOOGLE_CREDENTIAL_CHANGED", outcome(googleOperations.get("NOT_STARTED")));
            assertEquals("SUCCEEDED/null", outcome(googleOperations.get("SUCCEEDED")));
            assertEquals("NOT_STARTED/null", outcome(sharePointLive));

            jdbc.sql("UPDATE credentials SET status = 'NEEDS_UPDATE' WHERE id = :id")
                    .param("id", driftedCredential).update();
            assertEquals("CANCELLED/SOURCE_SHAREPOINT_CREDENTIAL_CHANGED", outcome(driftedLive));

            jdbc.sql("DELETE FROM credentials WHERE id = :id").param("id", looseCredential).update();
            assertEquals("CANCELLED/SOURCE_SHAREPOINT_CREDENTIAL_CHANGED", outcome(looseLive));
            assertEquals(0, count("source_selection_operations WHERE id = :id AND credential_id IS NOT NULL", looseLive));

            jdbc.sql("UPDATE connector_credential_pairs SET status = 'DELETING' WHERE id = :id")
                    .param("id", sharePointSource).update();
            assertEquals("CANCELLED/SOURCE_DELETING", outcome(sharePointOperations.get("IN_PROGRESS")));
            assertEquals("NOT_STARTED/null", outcome(sharePointLive));

            UUID memberLive = pending("SHAREPOINT", UUID.randomUUID(), sharePointCredential, otherActor);
            UUID ownerLive = pending("GOOGLE_DRIVE", UUID.randomUUID(), googleCredential, actor);
            jdbc.sql("UPDATE tenant_memberships SET status = 'INACTIVE' WHERE actor_id = :actor")
                    .param("actor", otherActor).update();
            assertEquals("CANCELLED/IAM_ACCESS_DENIED", outcome(memberLive));
            assertEquals("CANCELLED/IAM_ACCESS_DENIED", outcome(sharePointLive));
            assertEquals("NOT_STARTED/null", outcome(ownerLive));

            jdbc.sql("UPDATE tenants SET status = 'INACTIVE' WHERE id = :tenant").param("tenant", tenant).update();
            assertEquals("CANCELLED/IAM_ACCESS_DENIED", outcome(ownerLive));
            assertEquals(0, count("source_selection_operations WHERE " + LIVE));

            assertEquals(1, count("source_selection_operations WHERE source_id = :id", driveSource));
            jdbc.sql("DELETE FROM connector_credential_pairs WHERE id = :id").param("id", driveSource).update();
            assertEquals(0, count("source_selection_operations WHERE source_id = :id", driveSource));
            assertEquals(0, count("source_sync_state WHERE source_id = :id", driveSource));
            assertEquals(0, count("google_drive_sources WHERE source_id = :id", driveSource));
        }
    }

    private void seed(DataSource database) {
        jdbc = JdbcClient.create(database);
        tenant = UUID.randomUUID();
        actor = UUID.randomUUID();
        otherActor = UUID.randomUUID();
        googleCredential = UUID.randomUUID();
        sharePointCredential = UUID.randomUUID();
        driftedCredential = UUID.randomUUID();
        looseCredential = UUID.randomUUID();
        driveSource = UUID.randomUUID();
        sharePointSource = UUID.randomUUID();
        googleOperations.clear();
        sharePointOperations.clear();
        jdbc.sql("INSERT INTO tenants(id,slug,display_name,status,bootstrap_reference) VALUES(:id,'v134','V134','ACTIVE','TEST')")
                .param("id", tenant).update();
        for (UUID member : List.of(actor, otherActor)) {
            jdbc.sql("INSERT INTO actors(id) VALUES(:id)").param("id", member).update();
            jdbc.sql("INSERT INTO tenant_memberships(tenant_id,actor_id,role,status) VALUES(:tenant,:actor,'MEMBER','ACTIVE')")
                    .param("tenant", tenant).param("actor", member).update();
        }
        credential(googleCredential, "GOOGLE_OAUTH", "ACTIVE");
        jdbc.sql("""
                INSERT INTO google_drive_credentials (tenant_id, credential_id, account_subject, account_email,
                    granted_scopes, connection_status, credential_revision, payload_revision, auth_method,
                    refresh_token_ciphertext, refresh_token_nonce, key_version,
                    oauth_client_ciphertext, oauth_client_nonce, oauth_client_key_version)
                VALUES (:tenant, :id, 'subject', 'owner@example.test', 'openid', 'ACTIVE', 3, 5, 'OAUTH',
                    :secret, :nonce, 'v1', :secret, :nonce, 'v1')
                """).param("tenant", tenant).param("id", googleCredential).param("secret", new byte[32])
                .param("nonce", new byte[12]).update();
        // The credential both copies agree on, one whose copies drifted apart, and one nothing uses.
        sharePointCredential(sharePointCredential, "NEEDS_UPDATE", "NEEDS_UPDATE", 8, 2);
        sharePointCredential(driftedCredential, "NEEDS_UPDATE", "ACTIVE", 2, 4);
        sharePointCredential(looseCredential, "ACTIVE", "ACTIVE", 6, 6);
        credential(UUID.randomUUID(), "NO_AUTH", "ACTIVE");

        pair(driveSource, "GOOGLE_DRIVE", googleCredential);
        jdbc.sql("""
                INSERT INTO google_drive_sources (tenant_id, source_id, scope_mode, revision, generation,
                    schedule_revision, sync_interval_minutes, sync_paused, next_sync_at, last_synced_at)
                VALUES (:tenant, :source, 'GENERAL', 4, 6, 2, 45, TRUE,
                    TIMESTAMPTZ '2026-09-02 10:00:00+00', TIMESTAMPTZ '2026-09-01 10:00:00+00')
                """).param("tenant", tenant).param("source", driveSource).update();
        pair(sharePointSource, "SHAREPOINT", sharePointCredential);
        jdbc.sql("""
                INSERT INTO sharepoint_sources (tenant_id, source_id, scope_mode, prune_interval_hours, scope_revision,
                    generation, schedule_revision, sync_interval_minutes, sync_paused, next_sync_at, last_synced_at)
                VALUES (:tenant, :source, 'ALL_SITES', 72, 9, 3, 5, 120, FALSE,
                    TIMESTAMPTZ '2026-09-02 10:00:00+00', TIMESTAMPTZ '2026-09-01 10:00:00+00')
                """).param("tenant", tenant).param("source", sharePointSource).update();

        for (String status : STATUSES) {
            UUID google = UUID.randomUUID();
            googleOperations.put(status, google);
            jdbc.sql("""
                    INSERT INTO google_drive_selection_operations (id, tenant_id, source_id, actor_id, request_id,
                        request_hash, credential_id, credential_revision, scope_revision, discovery_revision,
                        scope_mode, source_name, max_requests, max_metadata, max_roots, max_request_bytes,
                        request_count, elapsed_millis, ancestor_count, metadata_count, status, claim_token,
                        lease_expires_at, delivery_id, dispatch_attempts, processing_attempts, failure_attempts,
                        error_code, origin_trace_id, origin_span_id, started_at, completed_at, group_ids, access_type)
                    VALUES (:id, :tenant, :source, :actor, :request, 'google-hash', :credential, 3, 4, 7, 'GENERAL',
                        'Drive', 900, 800, 700, 600, 5, 1234, 1, 1, :status, :claim, :lease, :delivery, 2, 3, 1,
                        :code, 'trace', 'span', CURRENT_TIMESTAMP, :completed, CAST(:groups AS jsonb), 'SYNC')
                    """).param("id", google).param("tenant", tenant)
                    .param("source", "NOT_STARTED".equals(status) ? driveSource : UUID.randomUUID())
                    .param("actor", actor).param("request", UUID.randomUUID()).param("credential", googleCredential)
                    .param("status", status).param("claim", "IN_PROGRESS".equals(status) ? UUID.randomUUID() : null)
                    .param("lease", "IN_PROGRESS".equals(status) ? Timestamp.valueOf("2030-01-01 00:00:00") : null)
                    .param("delivery", UUID.randomUUID()).param("code", "FAILED".equals(status) ? "SOURCE_GOOGLE_NOT_FOUND" : null)
                    .param("completed", STATUSES.indexOf(status) > 1 ? Timestamp.valueOf("2026-09-01 12:00:00") : null)
                    .param("groups", "[\"" + UUID.randomUUID() + "\"]").update();
            jdbc.sql("""
                    INSERT INTO google_drive_selection_entries (tenant_id, operation_id, file_id, kind)
                    VALUES (:tenant, :operation, 'root', 'ROOT')
                    """).param("tenant", tenant).param("operation", google).update();
            jdbc.sql("""
                    INSERT INTO google_drive_selection_ancestors (tenant_id, operation_id, file_id, kind, ancestor_id)
                    VALUES (:tenant, :operation, 'root', 'ROOT', 'parent')
                    """).param("tenant", tenant).param("operation", google).update();
            jdbc.sql("""
                    INSERT INTO google_drive_selection_metadata (tenant_id, operation_id, lookup_id, file_id, name,
                        mime_type, version, trashed, parents)
                    VALUES (:tenant, :operation, 'root', 'root', 'Root', 'application/vnd.google-apps.folder', '1',
                        FALSE, ARRAY['parent'])
                    """).param("tenant", tenant).param("operation", google).update();

            UUID sharePoint = UUID.randomUUID();
            sharePointOperations.put(status, sharePoint);
            jdbc.sql("""
                    INSERT INTO sharepoint_selection_operations (id, tenant_id, source_id, actor_id, request_id,
                        request_hash, credential_id, credential_revision, scope_revision, scope_mode, source_name,
                        access_type, group_ids, include_documents, include_pages, sync_interval_minutes,
                        prune_interval_hours, max_requests, max_roots, max_request_bytes, request_count,
                        elapsed_millis, status, failure_attempts, error_code, completed_at)
                    VALUES (:id, :tenant, :source, :actor, :request, 'sharepoint-hash', :credential, 8, 9,
                        'ALL_SITES', NULL, NULL, '[]', FALSE, TRUE, 90, 48, 500, 400, 300, 2, 99, :status, 4, :code,
                        :completed)
                    """).param("id", sharePoint).param("tenant", tenant)
                    .param("source", "IN_PROGRESS".equals(status) ? sharePointSource : UUID.randomUUID())
                    .param("actor", actor).param("request", UUID.randomUUID())
                    .param("credential", sharePointCredential).param("status", status)
                    .param("code", "CANCELLED".equals(status) ? "SOURCE_DELETING" : null)
                    .param("completed", STATUSES.indexOf(status) > 1 ? Timestamp.valueOf("2026-09-01 12:00:00") : null)
                    .update();
            jdbc.sql("""
                    INSERT INTO sharepoint_selection_entries (tenant_id, operation_id, position, kind, value)
                    VALUES (:tenant, :operation, 0, 'ROOT', 'https://contoso.sharepoint.com/sites/Finance')
                    """).param("tenant", tenant).param("operation", sharePoint).update();
        }
    }

    private void credential(UUID id, String kind, String status) {
        jdbc.sql("INSERT INTO credentials(id,tenant_id,name,credential_kind,status) VALUES(:id,:tenant,:kind,:kind,:status)")
                .param("id", id).param("tenant", tenant).param("kind", kind).param("status", status).update();
    }

    private void sharePointCredential(UUID id, String status, String connectionStatus, long revision, long payloadRevision) {
        credential(id, "SHAREPOINT_APP", status);
        jdbc.sql("""
                INSERT INTO sharepoint_credentials (tenant_id, credential_id, directory_id, client_id, cloud,
                    auth_method, connection_status, credential_revision, payload_revision, secret_ciphertext,
                    secret_nonce, secret_key_version)
                VALUES (:tenant, :id, :directory, :client, 'GLOBAL', 'CLIENT_SECRET', :status, :revision, :payload,
                    :secret, :nonce, 'v1')
                """).param("tenant", tenant).param("id", id).param("directory", UUID.randomUUID())
                .param("client", UUID.randomUUID()).param("status", connectionStatus).param("revision", revision)
                .param("payload", payloadRevision).param("secret", new byte[32]).param("nonce", new byte[12]).update();
    }

    private void pair(UUID id, String type, UUID credential) {
        jdbc.sql("INSERT INTO connectors(id,tenant_id,name,connector_type,status) VALUES(:id,:tenant,:type,:type,'ACTIVE')")
                .param("id", id).param("tenant", tenant).param("type", type).update();
        jdbc.sql("""
                INSERT INTO connector_credential_pairs(id,tenant_id,connector_id,credential_id,access_type,status)
                VALUES(:id,:tenant,:id,:credential,'PRIVATE','ACTIVE')
                """).param("id", id).param("tenant", tenant).param("credential", credential).update();
    }

    /** A request accepted after the migration, waiting to be verified. */
    private UUID pending(String type, UUID source, UUID credential, UUID submitter) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO source_selection_operations (id, tenant_id, source_id, source_type, actor_id, request_id,
                    request_hash, credential_id, credential_revision, scope_revision, max_requests, max_roots,
                    max_request_bytes)
                VALUES (:id, :tenant, :source, :type, :actor, :request, 'hash', :credential, 1, 1, 1, 1, 1)
                """).param("id", id).param("tenant", tenant).param("source", source).param("type", type)
                .param("actor", submitter).param("request", UUID.randomUUID()).param("credential", credential).update();
        return id;
    }

    private static void migrate(DataSource database) {
        var flyway = Flyway.configure().dataSource(database).locations("classpath:db/migration").target("134").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
    }

    private String outcome(UUID operation) {
        return jdbc.sql("SELECT status || '/' || COALESCE(error_code, 'null') FROM source_selection_operations WHERE id = :id")
                .param("id", operation).query(String.class).single();
    }

    private List<String> rows(String sql) {
        return jdbc.sql(sql).query(String.class).list();
    }

    private int count(String from) {
        return jdbc.sql("SELECT COUNT(*) FROM " + from).query(Integer.class).single();
    }

    private int count(String from, UUID id) {
        return jdbc.sql("SELECT COUNT(*) FROM " + from).param("id", id).query(Integer.class).single();
    }
}
