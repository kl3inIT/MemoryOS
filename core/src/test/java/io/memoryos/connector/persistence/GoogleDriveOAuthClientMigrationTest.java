package io.memoryos.connector.persistence;

import static org.junit.jupiter.api.Assertions.*;

import io.memoryos.TestDatabase;
import io.memoryos.connector.CredentialId;
import io.memoryos.iam.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class GoogleDriveOAuthClientMigrationTest {
    @Test
    void legacyGrantsLoseAuthorityWhileSourceRootsDocumentsAndFileWorkSurvive() throws Exception {
        try (var dataSource = TestDatabase.freshPostgres("23");
             var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                UUID tenant = UUID.randomUUID();
                UUID drive = UUID.randomUUID();
                UUID file = UUID.randomUUID();
                jdbc.sql("INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference) VALUES (:t, 'legacy', 'Legacy', 'ACTIVE', 'TEST')")
                        .param("t", tenant).update();
                seedSource(jdbc, tenant, drive, true);
                seedSource(jdbc, tenant, file, false);
                jdbc.sql("""
                        INSERT INTO google_drive_credentials (tenant_id, credential_id, account_subject, account_email, granted_scopes,
                            connection_status, refresh_token_ciphertext, refresh_token_nonce, key_version)
                        VALUES (:t, :id, 'unchanged-subject', 'owner@example.com', 'openid', 'ACTIVE', :ciphertext, :nonce, 'legacy-key')
                        """).param("t", tenant).param("id", drive).param("ciphertext", new byte[32]).param("nonce", new byte[12]).update();
                for (String sql : new String[]{
                        "INSERT INTO google_drive_sources (tenant_id, source_id) VALUES (:t, :id)",
                        "INSERT INTO google_drive_roots (tenant_id, source_id, file_id, name, mime_type) VALUES (:t, :id, 'explicit-root', 'Kept root', 'application/vnd.google-apps.folder')",
                        "INSERT INTO google_drive_membership (tenant_id, source_id, file_id, generation, eligible) VALUES (:t, :id, 'child', 1, TRUE)",
                        "INSERT INTO source_sync_attempts (id, tenant_id, source_id, scope_revision, credential_revision, generation, full_scan, status, claim_token, delivery_id) VALUES (:id, :t, :id, 1, 1, 1, TRUE, 'IN_PROGRESS', :id, :id)"}) {
                    jdbc.sql(sql).param("t", tenant).param("id", drive).update();
                }
                connection.commit();
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                        .target("24").load().migrate();

                var credential = jdbc.sql("SELECT * FROM google_drive_credentials").query().singleRow();
                assertEquals("NEEDS_REAUTHORIZATION", credential.get("connection_status"));
                assertEquals(2L, credential.get("credential_revision"));
                assertEquals(2L, credential.get("payload_revision"));
                assertEquals("unchanged-subject", credential.get("account_subject"));
                assertNull(credential.get("refresh_token_ciphertext"));
                assertNull(credential.get("refresh_token_nonce"));
                assertNull(credential.get("key_version"));
                assertNull(credential.get("oauth_client_ciphertext"));
                assertEquals("NEEDS_REAUTHORIZATION", jdbc.sql("SELECT status FROM credentials WHERE id = :id").param("id", drive).query(String.class).single());
                assertEquals("explicit-root", jdbc.sql("SELECT file_id FROM google_drive_roots").query(String.class).single());
                assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM documents WHERE title = 'Kept document'").query(Integer.class).single());
                assertEquals(2, jdbc.sql("SELECT COUNT(*) FROM stored_objects WHERE state = 'ACTIVE'").query(Integer.class).single());
                assertFalse(jdbc.sql("SELECT eligible FROM google_drive_membership").query(Boolean.class).single());
                assertFalse(jdbc.sql("SELECT retrieval_eligible FROM documents_by_connector_credential_pair WHERE connector_credential_pair_id = :id")
                        .param("id", drive).query(Boolean.class).single());
                assertTrue(jdbc.sql("SELECT retrieval_eligible FROM documents_by_connector_credential_pair WHERE connector_credential_pair_id = :id")
                        .param("id", file).query(Boolean.class).single());
                for (String table : new String[]{"source_sync_attempts", "index_attempts"}) {
                    var attempt = jdbc.sql("SELECT status, claim_token, delivery_id FROM " + table + " WHERE id = :id").param("id", drive).query().singleRow();
                    assertEquals("CANCELLED", attempt.get("status"));
                    assertNull(attempt.get("claim_token"));
                    assertNull(attempt.get("delivery_id"));
                }
                var unchanged = jdbc.sql("SELECT status, claim_token FROM index_attempts WHERE id = :id").param("id", file).query().singleRow();
                assertEquals("IN_PROGRESS", unchanged.get("status"));
                assertEquals(file, unchanged.get("claim_token"));
            } finally { connection.rollback(); }
        }
    }

    @Test
    void reusableCredentialMigrationPreservesEncryptedAuthorityAndAllSourceData() throws Exception {
        try (var dataSource = TestDatabase.freshPostgres("25");
             var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                UUID tenant = UUID.randomUUID();
                UUID drive = UUID.randomUUID();
                UUID file = UUID.randomUUID();
                String email = "long-account-" + "a".repeat(110) + "@example.com";
                jdbc.sql("INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference) VALUES (:t, 'reusable', 'Reusable', 'ACTIVE', 'TEST')")
                        .param("t", tenant).update();
                seedSource(jdbc, tenant, drive, true);
                seedSource(jdbc, tenant, file, false);
                var cipher = new GoogleDriveCredentialCipher(new byte[32], "preserved-key");
                byte[] plaintext = "preserved-refresh-token".getBytes(StandardCharsets.UTF_8);
                var token = cipher.encrypt(new TenantId(tenant), drive, plaintext);
                var client = cipher.encrypt(new TenantId(tenant), drive, "oauth-client", new byte[64]);
                jdbc.sql("""
                        INSERT INTO google_drive_credentials (tenant_id, credential_id, account_subject, account_email,
                            granted_scopes, connection_status, credential_revision, payload_revision,
                            refresh_token_ciphertext, refresh_token_nonce, key_version,
                            oauth_client_ciphertext, oauth_client_nonce, oauth_client_key_version)
                        VALUES (:t, :id, 'preserved-subject', :email, 'openid', 'ACTIVE', 7, 11,
                            :token, :nonce, :key, :client, :clientNonce, :key)
                        """).param("t", tenant).param("id", drive).param("email", email)
                        .param("token", token.ciphertext()).param("nonce", token.nonce()).param("key", token.keyVersion())
                        .param("client", client.ciphertext()).param("clientNonce", client.nonce()).update();
                jdbc.sql("INSERT INTO google_drive_sources (tenant_id, source_id, revision, generation) VALUES (:t, :id, 5, 8)")
                        .param("t", tenant).param("id", drive).update();
                jdbc.sql("INSERT INTO google_drive_roots (tenant_id, source_id, file_id, name, mime_type) VALUES (:t, :id, 'root-folder', 'Kept root', 'application/vnd.google-apps.folder')")
                        .param("t", tenant).param("id", drive).update();
                jdbc.sql("INSERT INTO google_drive_membership (tenant_id, source_id, file_id, root_id, generation, eligible) VALUES (:t, :id, 'child', 'root-folder', 8, TRUE)")
                        .param("t", tenant).param("id", drive).update();
                jdbc.sql("INSERT INTO source_sync_attempts (id, tenant_id, source_id, scope_revision, credential_revision, generation) VALUES (:id, :t, :id, 5, 7, 8)")
                        .param("t", tenant).param("id", drive).update();
                var snapshots = new LinkedHashMap<String, List<String>>();
                for (String table : List.of("google_drive_credentials", "connectors", "connector_credential_pairs",
                        "google_drive_sources", "google_drive_roots", "google_drive_membership", "source_sync_attempts",
                        "connector_items", "connector_item_versions", "documents", "documents_by_connector_credential_pair", "index_attempts")) {
                    snapshots.put(table, jdbc.sql("SELECT row_to_json(r)::text FROM " + table + " r ORDER BY row_to_json(r)::text")
                            .query(String.class).list());
                }
                connection.commit();
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                        .target("26").load().migrate();
                for (var snapshot : snapshots.entrySet()) {
                    assertEquals(snapshot.getValue(), jdbc.sql("SELECT row_to_json(r)::text FROM " + snapshot.getKey() + " r ORDER BY row_to_json(r)::text")
                            .query(String.class).list(), snapshot.getKey());
                }
                var credentials = new JdbcGoogleDriveCredentialRepository(jdbc, new JdbcSourceRepository(jdbc),
                        new GoogleDriveCredentialConfiguration(Base64.getEncoder().encodeToString(new byte[32]), "preserved-key"),
                        new JdbcSourceDocumentRepository(jdbc), new JdbcSourceSyncRepository(jdbc));
                var stored = credentials.readUsable(new TenantId(tenant), new CredentialId(drive));
                assertArrayEquals(plaintext, credentials.decrypt(new TenantId(tenant), stored));
                var catalog = credentials.list(new TenantId(tenant)).getFirst();
                assertEquals(new CredentialId(drive), catalog.id());
                assertEquals(email.substring(0, 120), catalog.name());
                assertEquals(email, catalog.accountEmail());
                assertEquals(7, catalog.credentialRevision());
                assertEquals(1, catalog.sourceCount());
                assertEquals("No authentication", jdbc.sql("SELECT name FROM credentials WHERE id = :id").param("id", file).query(String.class).single());
                UUID second = UUID.randomUUID();
                jdbc.sql("INSERT INTO connectors (id, tenant_id, name, connector_type, status) VALUES (:id, :t, 'Second source', 'GOOGLE_DRIVE', 'ACTIVE')")
                        .param("id", second).param("t", tenant).update();
                jdbc.sql("INSERT INTO connector_credential_pairs (id, tenant_id, connector_id, credential_id, access_type, status) VALUES (:id, :t, :id, :credential, 'RESTRICTED', 'NOT_STARTED')")
                        .param("id", second).param("t", tenant).param("credential", drive).update();
                assertEquals(2, credentials.list(new TenantId(tenant)).getFirst().sourceCount());
                jdbc.sql("INSERT INTO credentials (id, tenant_id, name, credential_kind, status) VALUES (:id, :t, 'Another account', 'GOOGLE_OAUTH', 'ACTIVE')")
                        .param("id", UUID.randomUUID()).param("t", tenant).update();
                var savepoint = connection.setSavepoint();
                assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                        jdbc.sql("INSERT INTO credentials (id, tenant_id, name, credential_kind, status) VALUES (:id, :t, 'Duplicate no auth', 'NO_AUTH', 'ACTIVE')")
                                .param("id", UUID.randomUUID()).param("t", tenant).update());
                connection.rollback(savepoint);
            } finally { connection.rollback(); }
        }
    }

    @Test
    void scheduleMigrationPreservesDueTimestampsAndEnforcesPositiveStoredValues() throws Exception {
        try (var dataSource = TestDatabase.freshPostgres("26");
             var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                UUID tenant = UUID.randomUUID();
                UUID source = UUID.randomUUID();
                jdbc.sql("INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference) VALUES (:t, 'schedule', 'Schedule', 'ACTIVE', 'TEST')")
                        .param("t", tenant).update();
                jdbc.sql("INSERT INTO credentials (id, tenant_id, name, credential_kind, status) VALUES (:id, :t, 'Account', 'GOOGLE_OAUTH', 'REVOKED')")
                        .param("id", source).param("t", tenant).update();
                jdbc.sql("INSERT INTO connectors (id, tenant_id, name, connector_type, status) VALUES (:id, :t, 'Scheduled source', 'GOOGLE_DRIVE', 'ACTIVE')")
                        .param("id", source).param("t", tenant).update();
                jdbc.sql("INSERT INTO connector_credential_pairs (id, tenant_id, connector_id, credential_id, access_type, status) VALUES (:id, :t, :id, :id, 'RESTRICTED', 'ACTIVE')")
                        .param("id", source).param("t", tenant).update();
                jdbc.sql("""
                        INSERT INTO google_drive_sources (tenant_id, source_id, revision, generation, next_sync_at, last_synced_at, error_code)
                        VALUES (:t, :id, 7, 11, '2001-02-03T04:05:06Z', '2001-02-03T04:00:06Z', 'SOURCE_GOOGLE_UNAVAILABLE')
                        """).param("id", source).param("t", tenant).update();
                String before = jdbc.sql("SELECT to_jsonb(s)::text FROM google_drive_sources s").query(String.class).single();

                connection.commit();
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                        .target("27").load().migrate();

                assertEquals(before, jdbc.sql("SELECT (to_jsonb(s) - 'sync_interval_minutes' - 'schedule_revision')::text FROM google_drive_sources s")
                        .query(String.class).single());
                assertEquals(5, jdbc.sql("SELECT sync_interval_minutes FROM google_drive_sources").query(Integer.class).single());
                assertEquals(1L, jdbc.sql("SELECT schedule_revision FROM google_drive_sources").query(Long.class).single());
                for (String assignment : List.of("sync_interval_minutes = 0", "sync_interval_minutes = -1",
                        "sync_interval_minutes = NULL", "sync_interval_minutes = 2147483648",
                        "schedule_revision = 0", "schedule_revision = NULL")) {
                    var savepoint = connection.setSavepoint();
                    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                            () -> jdbc.sql("UPDATE google_drive_sources SET " + assignment).update());
                    connection.rollback(savepoint);
                }
                UUID created = UUID.randomUUID();
                jdbc.sql("INSERT INTO connectors (id, tenant_id, name, connector_type, status) VALUES (:id, :t, 'New source', 'GOOGLE_DRIVE', 'ACTIVE')")
                        .param("id", created).param("t", tenant).update();
                jdbc.sql("INSERT INTO connector_credential_pairs (id, tenant_id, connector_id, credential_id, access_type, status) VALUES (:id, :t, :id, :credential, 'RESTRICTED', 'NOT_STARTED')")
                        .param("id", created).param("t", tenant).param("credential", source).update();
                jdbc.sql("INSERT INTO google_drive_sources (tenant_id, source_id) VALUES (:t, :id)")
                        .param("t", tenant).param("id", created).update();
                assertEquals(5, jdbc.sql("SELECT sync_interval_minutes FROM google_drive_sources WHERE source_id = :id")
                        .param("id", created).query(Integer.class).single());
                assertEquals(1L, jdbc.sql("SELECT schedule_revision FROM google_drive_sources WHERE source_id = :id")
                        .param("id", created).query(Long.class).single());
            } finally { connection.rollback(); }
        }
    }

    @Test
    void scopeMigrationDefaultsExistingSelectionsToSpecificWithoutChangingDataOrSchedules() throws Exception {
        try (var dataSource = TestDatabase.freshPostgres("25");
             var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                UUID tenant = UUID.randomUUID();
                UUID source = UUID.randomUUID();
                jdbc.sql("INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference) VALUES (:t, 'scope', 'Scope', 'ACTIVE', 'TEST')")
                        .param("t", tenant).update();
                seedSource(jdbc, tenant, source, true);
                seedSource(jdbc, tenant, UUID.randomUUID(), false);
                jdbc.sql("""
                        INSERT INTO google_drive_credentials (tenant_id, credential_id, account_subject, account_email,
                            granted_scopes, connection_status, credential_revision, payload_revision)
                        VALUES (:t, :id, 'preserved-account', 'owner@example.test', 'openid', 'REVOKED', 7, 11)
                        """).param("t", tenant).param("id", source).update();
                connection.commit();
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                        .target("27").load().migrate();
                jdbc.sql("""
                        INSERT INTO google_drive_sources (tenant_id, source_id, revision, generation, sync_interval_minutes,
                            schedule_revision, next_sync_at, last_synced_at)
                        VALUES (:t, :id, 4, 9, 1, 6, '2001-02-03T04:05:06Z', '2001-02-03T04:04:06Z')
                        """).param("t", tenant).param("id", source).update();
                for (String file : List.of("sheet-one", "sheet-two", "sheet-three")) {
                    jdbc.sql("""
                            INSERT INTO google_drive_roots (tenant_id, source_id, file_id, name, mime_type)
                            VALUES (:t, :id, :file, :file, 'application/vnd.google-apps.spreadsheet')
                            """).param("t", tenant).param("id", source).param("file", file).update();
                }
                jdbc.sql("INSERT INTO google_drive_membership (tenant_id, source_id, file_id, root_id, generation, eligible) VALUES (:t, :id, 'sheet-one', 'sheet-one', 9, TRUE)")
                        .param("t", tenant).param("id", source).update();
                jdbc.sql("INSERT INTO source_sync_attempts (id, tenant_id, source_id, scope_revision, credential_revision, generation) VALUES (:id, :t, :id, 4, 7, 9)")
                        .param("t", tenant).param("id", source).update();
                var snapshots = new LinkedHashMap<String, List<String>>();
                for (String table : List.of("credentials", "google_drive_credentials", "connectors", "connector_credential_pairs",
                        "google_drive_roots", "google_drive_membership", "source_sync_attempts", "connector_items",
                        "connector_item_versions", "documents", "documents_by_connector_credential_pair", "index_attempts")) {
                    snapshots.put(table, jdbc.sql("SELECT to_jsonb(r)::text FROM " + table + " r ORDER BY to_jsonb(r)::text")
                            .query(String.class).list());
                }
                String sourceBefore = jdbc.sql("SELECT to_jsonb(s)::text FROM google_drive_sources s").query(String.class).single();

                connection.commit();
                Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                        .target("28").load().migrate();

                assertEquals(sourceBefore, jdbc.sql("SELECT (to_jsonb(s) - 'scope_mode')::text FROM google_drive_sources s").query(String.class).single());
                for (var snapshot : snapshots.entrySet()) {
                    assertEquals(snapshot.getValue(), jdbc.sql("SELECT to_jsonb(r)::text FROM " + snapshot.getKey() + " r ORDER BY to_jsonb(r)::text")
                            .query(String.class).list(), snapshot.getKey());
                }
                assertEquals("SPECIFIC", jdbc.sql("SELECT scope_mode FROM google_drive_sources").query(String.class).single());
                for (String assignment : List.of("scope_mode = NULL", "scope_mode = 'UNKNOWN'", "scope_mode = 'general'")) {
                    var savepoint = connection.setSavepoint();
                    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                            () -> jdbc.sql("UPDATE google_drive_sources SET " + assignment).update());
                    connection.rollback(savepoint);
                }
                jdbc.sql("UPDATE google_drive_sources SET scope_mode = 'GENERAL'").update();
                assertEquals("GENERAL", jdbc.sql("SELECT scope_mode FROM google_drive_sources").query(String.class).single());
            } finally { connection.rollback(); }
        }
    }

    private static void seedSource(JdbcClient jdbc, UUID tenant, UUID id, boolean drive) {
        jdbc.sql("INSERT INTO credentials (id, tenant_id, credential_kind, status) VALUES (:id, :t, :kind, 'ACTIVE')")
                .param("id", id).param("t", tenant).param("kind", drive ? "GOOGLE_OAUTH" : "NO_AUTH").update();
        jdbc.sql("INSERT INTO connectors (id, tenant_id, name, connector_type, status) VALUES (:id, :t, 'Kept source', :type, 'ACTIVE')")
                .param("id", id).param("t", tenant).param("type", drive ? "GOOGLE_DRIVE" : "FILE").update();
        jdbc.sql("INSERT INTO connector_credential_pairs (id, tenant_id, connector_id, credential_id, access_type, status, document_count) VALUES (:id, :t, :id, :id, :access, 'ACTIVE', 1)")
                .param("id", id).param("t", tenant).param("access", drive ? "RESTRICTED" : "PUBLIC").update();
        for (String sql : new String[]{
                "INSERT INTO stored_objects (id, tenant_id, object_key, filename, declared_media_type, size_bytes, content_sha256, state, expires_at) VALUES (:id, :t, CAST(:id AS TEXT), 'Kept.txt', 'text/plain', 1, REPEAT('a',64), 'ACTIVE', CURRENT_TIMESTAMP)",
                "INSERT INTO connector_items (id, tenant_id, connector_id, content_sha256, status) VALUES (:id, :t, :id, REPEAT('a',64), 'INDEXED')",
                "INSERT INTO connector_item_versions (id, tenant_id, connector_id, connector_item_id, revision_number, filename, content_sha256, size_bytes, stored_object_id) VALUES (:id, :t, :id, :id, 1, 'Kept.txt', REPEAT('a',64), 1, :id)",
                "UPDATE connector_items SET current_version_id = :id WHERE tenant_id = :t AND id = :id",
                "INSERT INTO documents (id, tenant_id, status, title) VALUES (:id, :t, 'ELIGIBLE', 'Kept document')",
                "INSERT INTO documents_by_connector_credential_pair (tenant_id, connector_id, connector_credential_pair_id, document_id, connector_item_id, retrieval_eligible) VALUES (:t, :id, :id, :id, :id, TRUE)",
                "INSERT INTO index_attempts (id, tenant_id, connector_id, connector_credential_pair_id, connector_item_id, connector_item_version_id, pair_sequence, item_sequence, status, claim_token, delivery_id) VALUES (:id, :t, :id, :id, :id, :id, 1, 1, 'IN_PROGRESS', :id, :id)"}) {
            jdbc.sql(sql).param("id", id).param("t", tenant).update();
        }
    }
}
