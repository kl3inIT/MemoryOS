package io.memoryos.connector.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.connector.SourceId;
import io.memoryos.connector.SourceManagementService;
import io.memoryos.connector.persistence.JdbcConnectorCleanupPort;
import io.memoryos.connector.persistence.JdbcIndexAttemptRepository;
import io.memoryos.connector.persistence.JdbcSourceDocumentRepository;
import io.memoryos.connector.persistence.JdbcSourceItemRepository;
import io.memoryos.connector.persistence.JdbcSourceQueryRepository;
import io.memoryos.connector.persistence.JdbcSourceRepository;
import io.memoryos.identity.ActorId;
import io.memoryos.organization.persistence.JdbcOrganizationAccessResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class PostgresSourceConcurrencyTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
    ).asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("memoryos")
            .withUsername("memoryos")
            .withPassword("memoryos");

    private JdbcClient jdbcClient;
    private SourceManagementService service;
    private JdbcIndexAttemptRepository attempts;
    private ActorId owner;
    private JdbcConnectorCleanupPort cleanup;

    private UUID organizationId;

    @BeforeEach
    void migrateAndSeed() throws Exception {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
            new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/V1__create_identity_tables.sql"),
                    new ClassPathResource("db/migration/V2__create_initial_organization_and_sessions.sql"),
                    new ClassPathResource("db/migration/V3__create_organization_invitations.sql"),
                    new ClassPathResource("db/migration/V4__collapse_workspace_into_organization.sql"),
                    new ClassPathResource("db/migration/V5__create_file_source_and_document_schema.sql")
            ).populate(connection);
        }
        jdbcClient = JdbcClient.create(dataSource);
        organizationId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:id)").param("id", actorId).update();
        jdbcClient.sql("""
                        INSERT INTO organizations (
                            id, slug, display_name, status, bootstrap_reference
                        ) VALUES (
                            :id, 'source-concurrency', 'Source concurrency', 'ACTIVE', 'MEM-35-TEST'
                        )
                        """)
                .param("id", organizationId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO organization_memberships (
                            organization_id, actor_id, role, status
                        ) VALUES (:organizationId, :actorId, 'OWNER', 'ACTIVE')
                        """)
                .param("organizationId", organizationId)
                .param("actorId", actorId)
                .update();
        owner = new ActorId(actorId);

        var sourceDocuments = new JdbcSourceDocumentRepository(jdbcClient);
        attempts = new JdbcIndexAttemptRepository(jdbcClient, sourceDocuments);
        var documentCommands = new io.memoryos.document.DocumentCommandService() {
            @Override
            public io.memoryos.document.DocumentId publish(
                    io.memoryos.organization.OrganizationId ignoredOrganization,
                    io.memoryos.document.DocumentId ignoredDocument,
                    io.memoryos.document.DocumentContent ignoredContent,
                    String ignoredSha
            ) {
                throw new UnsupportedOperationException("not used by claim tests");
            }

            @Override
            public void removeUnreferenced(
                    io.memoryos.organization.OrganizationId ignoredOrganization,
                    java.util.List<io.memoryos.document.DocumentId> ignoredDocuments
            ) {
            }
        };
        cleanup = new JdbcConnectorCleanupPort(jdbcClient, sourceDocuments, documentCommands);
        var target = new DefaultSourceManagementService(
                new JdbcSourceRepository(jdbcClient),
                new JdbcSourceItemRepository(jdbcClient),
                attempts,
                sourceDocuments,
                new JdbcSourceQueryRepository(jdbcClient),
                new JdbcOrganizationAccessResolver(jdbcClient)
        );
        service = transactionalProxy(target, new DataSourceTransactionManager(dataSource));
    }

    @Test
    void concurrentSourceCreationSharesOneNoAuthCredential() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.createFileSource(owner, "First"));
            var second = executor.submit(() -> service.createFileSource(owner, "Second"));
            first.get();
            second.get();
        }
        assertEquals(1L, count("credentials"));
        assertEquals(2L, count("connectors"));
        assertEquals(2L, count("connector_credential_pairs"));
    }

    @Test
    void duplicateUploadConvergesOnOneItemVersionAndAttempt() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Files").source().id();
        byte[] content = "same MemoryOS content".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.upload(owner, sourceId, "first.txt", content, sha256));
            var second = executor.submit(() -> service.upload(owner, sourceId, "second.txt", content, sha256));
            var firstResult = first.get();
            var secondResult = second.get();
            assertEquals(firstResult.item().id(), secondResult.item().id());
            assertEquals(firstResult.operation().id(), secondResult.operation().id());
        }
        assertEquals(1L, count("connector_items"));
        assertEquals(1L, count("connector_item_versions"));
        assertEquals(1L, count("index_attempts"));
    }

    @Test
    void staleWorkerTokenCannotCompleteAfterLeaseReclaimAndTenantFksFailClosed() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Lease").source().id();
        byte[] content = "lease content".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        service.upload(owner, sourceId, "lease.txt", content, sha256);
        var stale = attempts.claim(1).getFirst();
        jdbcClient.sql("""
                        UPDATE index_attempts
                        SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE id = :id
                        """)
                .param("id", stale.operationId().value())
                .update();
        var current = attempts.claim(1).getFirst();
        assertNotEquals(stale.claimToken(), current.claimToken());
        assertFalse(attempts.fail(stale, "SOURCE_EXTRACTION_TIMEOUT"));
        assertTrue(attempts.fail(current, "SOURCE_EXTRACTION_TIMEOUT"));

        UUID foreignOrganization = UUID.randomUUID();
        UUID foreignCredential = UUID.randomUUID();
        jdbcClient.sql("""
                        INSERT INTO organizations (id, slug, display_name, status, bootstrap_reference)
                        VALUES (:id, 'foreign', 'Foreign', 'ACTIVE', 'MEM-35-TEST')
                        """)
                .param("id", foreignOrganization)
                .update();
        jdbcClient.sql("""
                        INSERT INTO credentials (id, organization_id, credential_kind, status)
                        VALUES (:id, :organizationId, 'NO_AUTH', 'ACTIVE')
                        """)
                .param("id", foreignCredential)
                .param("organizationId", foreignOrganization)
                .update();
        UUID connectorId = jdbcClient.sql("SELECT id FROM connectors WHERE organization_id = :organizationId")
                .param("organizationId", organizationId)
                .query(UUID.class)
                .single();
        assertThrows(DataAccessException.class, () -> jdbcClient.sql("""
                        INSERT INTO connector_credential_pairs (
                            id, organization_id, connector_id, credential_id, access_type, status
                        ) VALUES (
                            :id, :organizationId, :connectorId, :credentialId, 'PUBLIC', 'NOT_STARTED'
                        )
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", organizationId)
                .param("connectorId", connectorId)
                .param("credentialId", foreignCredential)
                .update());
    }

    @Test
    void concurrentReindexAndCleanupLeaseReclaimRemainSingleFlight() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Single flight").source().id();
        byte[] content = "single flight".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var upload = service.upload(owner, sourceId, "single.txt", content, sha256);
        var initialWork = attempts.claim(1).getFirst();
        assertTrue(attempts.fail(initialWork, "SOURCE_EXTRACTION_TIMEOUT"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.reindex(owner, sourceId, upload.item().id()));
            var second = executor.submit(() -> service.reindex(owner, sourceId, upload.item().id()));
            assertEquals(first.get().id(), second.get().id());
        }
        assertEquals(2L, count("index_attempts"));

        service.deleteSource(owner, sourceId);
        var staleCleanup = cleanup.claim(1).getFirst();
        jdbcClient.sql("""
                        UPDATE connector_cleanup_attempts
                        SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                        WHERE id = :id
                        """)
                .param("id", staleCleanup.operationId().value())
                .update();
        var currentCleanup = cleanup.claim(1).getFirst();
        assertNotEquals(staleCleanup.claimToken(), currentCleanup.claimToken());
        assertFalse(cleanup.fail(staleCleanup, "SOURCE_CLEANUP_INTERNAL"));
        assertTrue(cleanup.fail(currentCleanup, "SOURCE_CLEANUP_INTERNAL"));
    }

    @Test
    void inactiveOrganizationCancelsPendingIndexWorkWithoutPublishing() throws Exception {
        SourceId sourceId = service.createFileSource(owner, "Inactive").source().id();
        byte[] content = "inactive content".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        service.upload(owner, sourceId, "inactive.txt", content, sha256);
        jdbcClient.sql("""
                        UPDATE organizations SET status = 'INACTIVE', updated_at = CURRENT_TIMESTAMP
                        WHERE id = :organizationId
                        """)
                .param("organizationId", organizationId)
                .update();

        assertTrue(attempts.claim(1).isEmpty());
        assertEquals(
                "CANCELLED",
                jdbcClient.sql("SELECT status FROM index_attempts")
                        .query(String.class)
                        .single()
        );
        assertEquals(0L, count("documents"));
        assertEquals(0L, count("documents_by_connector_credential_pair"));
    }

    private long count(String table) {
        return jdbcClient.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
    }

    private static SourceManagementService transactionalProxy(
            DefaultSourceManagementService target,
            DataSourceTransactionManager manager
    ) {
        var factory = new ProxyFactory(target);
        var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()
        );
        factory.addAdvice(interceptor);
        return (SourceManagementService) factory.getProxy();
    }
}
