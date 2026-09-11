package io.memoryos.api.source;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveOAuthClient;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.connector.SourceInputFormat;
import io.memoryos.api.ApiPostgresDatabase;
import io.memoryos.api.security.ActorAuthenticationToken;
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.document.DocumentId;
import io.memoryos.document.ExtractionArtifactPort;
import io.memoryos.iam.ActorId;
import io.memoryos.iam.IdentityContext;
import io.memoryos.ingestion.OperationDispatchPort;
import io.memoryos.ingestion.OperationWorkload;
import io.memoryos.ingestion.SourceContentExtractor;
import io.memoryos.ingestion.application.DefaultIngestionCoordinator;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectContent;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectMetadata;
import io.memoryos.objectstorage.ObjectStorage;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.StoredObjectRegistry;
import io.memoryos.objectstorage.UploadAuthorization;
import io.memoryos.objectstorage.UploadConstraints;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.swagger.v3.core.util.Json;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "memoryos.identity.audience=memoryos-api",
        "memoryos.google-drive.credential-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "memoryos.google-drive.credential-key-version=test-v1",
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "arconia.multitenancy.resolution.fixed.tenant-identifier=10000000-0000-0000-0000-000000000024",
        "memoryos.initial-tenant.id=10000000-0000-0000-0000-000000000024",
        "memoryos.initial-tenant.owner-subject=source-owner",
        "memoryos.initial-tenant.slug=sources",
        "memoryos.initial-tenant.display-name=Sources",
        "memoryos.initial-tenant.change-reference=MEM-35-TEST",
        "memoryos.search.endpoint=http://127.0.0.1:1",
})
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
@Import({
        SourceApiIntegrationTest.StorageTestConfiguration.class,
        io.memoryos.provider.file.FileProviderAutoConfiguration.class,
        io.memoryos.provider.SourceContentExtractorAutoConfiguration.class
})
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
class SourceApiIntegrationTest {
    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER =
            "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private io.memoryos.connector.SourceManagementService sourceManagement;

    @Autowired
    private SourceDocumentAccessResolver documentAccess;

    @Autowired
    private ConnectorIndexingPort indexingPort;

    @Autowired
    private ConnectorCleanupPort cleanupPort;

    @Autowired
    private OperationDispatchPort operationDispatch;

    @Autowired
    private DocumentCommandPort documents;

    @Autowired
    private SourceContentExtractor extractor;

    @Autowired
    private io.memoryos.connector.ConnectorSyncPort sourceSync;

    @Autowired
    private io.memoryos.connector.GoogleDriveSelectionProcessor selections;

    @Autowired
    private ExtractionArtifactPort extractionArtifacts;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private InMemoryObjectStorage objectStorage;

    @Autowired
    private StoredObjectRegistry storedObjects;

    @Autowired
    private GoogleDriveAuthorizationService googleAuthorizations;

    @MockitoBean
    private GoogleDriveProvider googleProvider;
    private GoogleDriveProvider.Session googleSession;

    private ActorAuthenticationToken owner;
    private ActorAuthenticationToken member;

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry registry) {
        ApiPostgresDatabase.configure(registry);
        registry.add("spring.security.oauth2.client.provider.memoryos.issuer-uri", () -> BROWSER_ISSUER);
        registry.add("memoryos.identity.keycloak.admin.server-url", () -> "http://127.0.0.1:1");
        registry.add("memoryos.google-drive.revoke-uri", () -> BROWSER_ISSUER + "/revoke");
        registry.add("memoryos.identity.keycloak.admin.client-secret", () -> "test-provisioner-secret");
        registry.add(
                "memoryos.identity.keycloak.admin.action-redirect-uri",
                () -> "http://127.0.0.1/invite/activate"
        );
    }

    @AfterAll
    static void stopIdentityServer() {
        IDENTITY_SERVER.stop(0);
    }

    @BeforeEach
    void seedActors() {
        UUID tenantId = jdbcClient.sql("SELECT id FROM tenants WHERE slug = 'sources'")
                .query(UUID.class)
                .single();
        UUID ownerActorId = ownerActorId();
        UUID memberActorId = UUID.fromString("5b38e8dd-6c42-41ff-b392-7942808ce3af");
        int actorCount = jdbcClient.sql("SELECT COUNT(*) FROM actors WHERE id = :id")
                .param("id", memberActorId)
                .query(Integer.class)
                .single();
        if (actorCount == 0) {
            jdbcClient.sql("INSERT INTO actors (id) VALUES (:id)")
                    .param("id", memberActorId)
                    .update();
        }
        int membershipCount = jdbcClient.sql("""
                        SELECT COUNT(*) FROM tenant_memberships
                        WHERE tenant_id = :tenantId AND actor_id = :actorId
                        """)
                .param("tenantId", tenantId)
                .param("actorId", memberActorId)
                .query(Integer.class)
                .single();
        if (membershipCount == 0) {
            jdbcClient.sql("""
                            INSERT INTO tenant_memberships (
                                tenant_id, actor_id, role, status
                            ) VALUES (:tenantId, :actorId, 'MEMBER', 'ACTIVE')
                            """)
                    .param("tenantId", tenantId)
                    .param("actorId", memberActorId)
                    .update();
        }
        owner = token(ownerActorId);
        member = token(memberActorId);
    }

    @Test
    @Transactional
    void itemPagesEnforceTheDefaultBoundAndFinishWithoutDuplicateRows() throws Exception {
        var actor = owner.getPrincipal().actorId();
        var source = sourceManagement.createFileSource(actor, "Paged API files", List.of());
        var expected = new HashSet<String>();
        for (int index = 0; index < 26; index++) {
            byte[] content = ("API page content " + index).getBytes(UTF_8);
            var authorization = sourceManagement.initiateUpload(actor, source.id(),
                    new io.memoryos.objectstorage.ObjectUploadSpecification(
                            "page-" + index + ".txt", "text/plain", content.length, InMemoryObjectStorage.checksum(content)));
            objectStorage.put(authorization.authorization().uri(), content);
            expected.add(sourceManagement.finalizeUpload(actor, source.id(), authorization.uploadId()).item().id().value().toString());
        }
        String firstBody = mockMvc.perform(get("/api/sources/{id}/items", source.id().value()).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(25))
                .andExpect(jsonPath("$.totalItems").value(26))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn().getResponse().getContentAsString();
        var first = io.swagger.v3.core.util.Json.mapper().readTree(firstBody);
        String secondBody = mockMvc.perform(get("/api/sources/{id}/items", source.id().value()).with(authentication(owner))
                        .param("cursor", first.path("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(26))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();
        var observed = new HashSet<String>();
        first.path("items").forEach(item -> assertTrue(observed.add(item.path("id").asText())));
        io.swagger.v3.core.util.Json.mapper().readTree(secondBody).path("items")
                .forEach(item -> assertTrue(observed.add(item.path("id").asText())));
        assertEquals(expected, observed);
        mockMvc.perform(get("/api/sources/{id}/items", source.id().value()).with(authentication(owner)).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(26))
                .andExpect(jsonPath("$.totalItems").value(26))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));

        String attemptsBody = mockMvc.perform(get("/api/sources/{id}/index-attempts", source.id().value())
                        .with(authentication(owner)).param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.totalItems").value(26))
                .andReturn().getResponse().getContentAsString();
        String next = io.swagger.v3.core.util.Json.mapper().readTree(attemptsBody).path("nextCursor").asText();
        mockMvc.perform(get("/api/sources/{id}/index-attempts", source.id().value())
                        .with(authentication(owner)).param("size", "5").param("cursor", next))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.totalItems").value(26));
        var empty = sourceManagement.createFileSource(actor, "Empty history", List.of());
        mockMvc.perform(get("/api/sources/{id}/items", empty.id().value()).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(0));
        mockMvc.perform(get("/api/sources/{id}/index-attempts", empty.id().value()).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(0));
        mockMvc.perform(get("/api/sources/{id}/index-attempts", source.id().value()).with(authentication(member)))
                .andExpect(status().isForbidden());
    }

    @Test
    @Transactional
    void runPageTotalsRemainFilteredAndSourceScopedAcrossPages() throws Exception {
        var actor = owner.getPrincipal().actorId();
        var source = sourceManagement.createFileSource(actor, "API run history", List.of());
        jdbcClient.sql("""
                UPDATE connectors SET connector_type = 'GOOGLE_DRIVE'
                WHERE id = (SELECT connector_id FROM connector_credential_pairs WHERE id = :source)
                """).param("source", source.id().value()).update();
        jdbcClient.sql("""
                INSERT INTO google_drive_sources (tenant_id, source_id, scope_mode)
                SELECT tenant_id, id, 'SPECIFIC' FROM connector_credential_pairs WHERE id = :source
                """).param("source", source.id().value()).update();
        jdbcClient.sql("""
                INSERT INTO source_sync_attempts (
                    id, tenant_id, source_id, scope_revision, credential_revision, generation,
                    history_version, trigger_kind, status, created_at, completed_at, run_completed_at,
                    acquired, unchanged, published, indexing_pending, indexing_failed, indexing_cancelled, indexing_superseded)
                SELECT gen_random_uuid(), pair.tenant_id, pair.id, 1, 1, run.ordinal,
                    1, run.trigger, 'SUCCEEDED', TIMESTAMPTZ '2026-01-01 00:00:00+00' + run.ordinal * INTERVAL '1 second',
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 0, 0, 0, 0, 0, 0
                FROM connector_credential_pairs pair
                CROSS JOIN (VALUES (1, 'MANUAL'), (2, 'MANUAL'), (3, 'SCHEDULED')) run(ordinal, trigger)
                WHERE pair.id = :source
                """).param("source", source.id().value()).update();
        String body = mockMvc.perform(get("/api/sources/{id}/runs", source.id().value()).with(authentication(owner))
                        .param("size", "1").param("status", "SUCCEEDED").param("trigger", "MANUAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn().getResponse().getContentAsString();
        var first = Json.mapper().readTree(body);
        mockMvc.perform(get("/api/sources/{id}/runs", source.id().value()).with(authentication(owner))
                        .param("size", "1").param("status", "SUCCEEDED").param("trigger", "MANUAL")
                        .param("cursor", first.path("nextCursor").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(org.hamcrest.Matchers.not(first.path("items").get(0).path("id").asText())))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
        var empty = sourceManagement.createFileSource(actor, "No source runs", List.of());
        mockMvc.perform(get("/api/sources/{id}/runs", empty.id().value()).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(0));
        mockMvc.perform(get("/api/sources/{id}/runs", source.id().value()).with(authentication(owner))
                        .param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(0));
        mockMvc.perform(get("/api/sources/{id}/runs", source.id().value()).with(authentication(member)))
                .andExpect(status().isForbidden());
    }

    @Test
    void indexesAndCleansUpOneFileThroughTheAuthorizedApi() throws Exception {
        String sourceBody = mockMvc.perform(post("/api/sources/file")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Product documentation\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String sourceId = Json.mapper().readTree(sourceBody)
                .path("id").textValue();

        byte[] file = "MemoryOS FILE connector content".getBytes(UTF_8);
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file));
        String authorizationBody = mockMvc.perform(post("/api/sources/{sourceId}/uploads", sourceId)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"filename":"knowledge.txt","mediaType":"text/plain","sizeBytes":%d,"sha256":"%s"}
                                """.formatted(file.length, checksum)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("PUT"))
                .andReturn().getResponse().getContentAsString();
        var authorization = Json.mapper().readTree(authorizationBody);
        String uploadId = authorization.path("uploadId").textValue();
        objectStorage.put(URI.create(authorization.path("uploadUrl").textValue()), file);
        String uploadBody = mockMvc.perform(post(
                        "/api/sources/{sourceId}/uploads/{uploadId}/finalize",
                        sourceId,
                        uploadId
                )
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.item.status").value("PENDING"))
                .andExpect(jsonPath("$.operation.status").value("NOT_STARTED"))
                .andReturn().getResponse().getContentAsString();
        String itemId = Json.mapper().readTree(uploadBody)
                .path("item").path("id").textValue();

        processDispatchedWork();
        UUID documentId = jdbcClient.sql("""
                        SELECT document_id FROM documents_by_connector_credential_pair
                        WHERE connector_credential_pair_id = :sourceId
                        """)
                .param("sourceId", UUID.fromString(sourceId))
                .query(UUID.class)
                .single();
        assertTrue(documentAccess.canRead(
                owner.getPrincipal().actorId(),
                new DocumentId(documentId)
        ));
        assertTrue(documentAccess.canRead(
                member.getPrincipal().actorId(),
                new DocumentId(documentId)
        ));
        mockMvc.perform(get("/api/sources/{sourceId}", sourceId).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.items").doesNotExist());
        mockMvc.perform(get("/api/sources/{sourceId}/items", sourceId).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(itemId))
                .andExpect(jsonPath("$.items[0].status").value("INDEXED"))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(post("/api/sources/{sourceId}/items/{itemId}/remove", sourceId, itemId)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("REMOVE_ITEM"));
        assertFalse(documentAccess.canRead(
                member.getPrincipal().actorId(),
                new DocumentId(documentId)
        ));
        mockMvc.perform(get("/api/sources/{sourceId}", sourceId).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingWork").value(true));
        mockMvc.perform(get("/api/sources/{sourceId}/items", sourceId).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("DELETING"));
        processDispatchedWork();
        mockMvc.perform(get("/api/sources/{sourceId}", sourceId).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingWork").value(false));
        mockMvc.perform(get("/api/sources/{sourceId}/items", sourceId).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));

        String deleteBody = mockMvc.perform(post("/api/sources/{sourceId}/delete", sourceId)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("DELETE_SOURCE"))
                .andReturn().getResponse().getContentAsString();
        String deleteOperationId = Json.mapper().readTree(deleteBody)
                .path("id").textValue();
        processDispatchedWork();
        mockMvc.perform(post("/api/sources/{sourceId}/delete", sourceId)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(deleteOperationId));
        mockMvc.perform(get("/api/sources/{sourceId}", sourceId).with(authentication(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
    }

    @Test
    void googleAuthorizationRequiresGlobalManagementAndCsrfBeforeOAuthClientValidation() throws Exception {
        mockMvc.perform(post("/api/credentials/google-drive/authorization")
                        .with(authentication(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Drive\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/credentials/google-drive/authorization")
                        .with(authentication(member))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Drive\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
        mockMvc.perform(post("/api/credentials/google-drive/authorization")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Drive\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GOOGLE_DRIVE_OAUTH_CLIENT_REQUIRED"));
    }

    @Test
    void malformedOAuthBodyCannotEchoUploadedClientSecret() throws Exception {
        var response = mockMvc.perform(post("/api/credentials/google-drive/authorization")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Drive\",\"oauthClientJson\":{\"client_secret\":\"must-not-escape\"}}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse();
        assertFalse(response.getContentAsString().contains("must-not-escape"));
    }

    @Test
    @Transactional
    void otherTenantOwnerCannotReadOrAttachKnownForeignCredentials() throws Exception {
        // Roll back this synthetic second Tenant together with the temporarily relaxed deployment constraint.
        jdbcClient.sql("ALTER TABLE tenants DROP CONSTRAINT uq_tenants_deployment_slot").update();
        var credential = googleCredential("Foreign tenant credential");
        String foreignSource = createGoogleSource(credential, "Foreign source", "foreign-root");
        UUID tenantId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:id)").param("id", actorId).update();
        jdbcClient.sql("""
                INSERT INTO tenants (id, slug, display_name, status, bootstrap_reference)
                VALUES (:id, :slug, 'Other tenant', 'ACTIVE', 'API-TEST')
                """).param("id", tenantId).param("slug", "other-" + tenantId).update();
        jdbcClient.sql("""
                INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                VALUES (:tenant, :actor, 'OWNER', 'ACTIVE')
                """).param("tenant", tenantId).param("actor", actorId).update();
        jdbcClient.sql("INSERT INTO iam_groups(tenant_id,id,name,system_key) VALUES (:tenant,:tenant,'Admin','ADMIN')")
                .param("tenant",tenantId).update();
        jdbcClient.sql("INSERT INTO iam_group_capability_grants(tenant_id,group_id,capability) VALUES (:tenant,:tenant,'IAM_ADMIN')")
                .param("tenant",tenantId).update();
        jdbcClient.sql("INSERT INTO iam_group_memberships(tenant_id,group_id,actor_id) VALUES (:tenant,:tenant,:actor)")
                .param("tenant",tenantId).param("actor",actorId).update();
        var otherOwner = token(actorId);
        mockMvc.perform(get("/api/sources/{id}", foreignSource).with(authentication(otherOwner)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/sources/{id}/items", foreignSource).with(authentication(otherOwner))
                        .param("cursor", "invalid"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", foreignSource).with(authentication(otherOwner))
                        .param("parentId", "foreign-root"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/credentials/google-drive").with(authentication(otherOwner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        for (UUID id : List.of(credential.value(), UUID.randomUUID())) {
            mockMvc.perform(delete("/api/credentials/google-drive/{id}", id)
                            .with(authentication(otherOwner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/credentials/google-drive/{id}/revoke", id)
                            .with(authentication(otherOwner)).header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedCredentialRevision\":1}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/credentials/google-drive/authorization")
                            .with(authentication(otherOwner)).header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Foreign\",\"credentialId\":\"" + id + "\",\"expectedCredentialRevision\":1}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(post("/api/sources/google-drive")
                            .with(authentication(otherOwner)).header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(googleSourceBody(new CredentialId(id), "Forbidden", "private-doc")))
                    .andExpect(status().isNotFound());
        }
            mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", foreignSource)
                            .with(authentication(otherOwner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":15}"))
                    .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", foreignSource)
                        .with(authentication(otherOwner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(replaceRequest(foreignSource, "{\"scopeMode\":\"GENERAL\",\"links\":[],\"linkedDocumentIds\":[]}")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sources/{id}/google-drive/linked-documents/discover", foreignSource)
                        .with(authentication(otherOwner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                .andExpect(status().isNotFound());
        jdbcClient.sql("UPDATE tenant_memberships SET status = 'INACTIVE' WHERE tenant_id = :tenant AND actor_id = :actor")
                .param("tenant", tenantId).param("actor", actorId).update();
        mockMvc.perform(get("/api/credentials/google-drive").with(authentication(otherOwner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void oneCredentialCreatesIndependentSourcesAndSharedRevocationPreservesBoth() throws Exception {
        var credential = googleCredential("Reusable owner account");
        String first = createGoogleSource(credential, "First source", "first-doc");
        String second = createGoogleSource(credential, "Second source", "second-doc");
        assertNotEquals(first, second);
        for (String source : List.of(first, second)) {
            mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentialId").value(credential.value().toString()))
                    .andExpect(jsonPath("$.credentialStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.scopeMode").value("SPECIFIC"));
            assertSelectedRoot(source, source.equals(first) ? "first-doc" : "second-doc");
        }
        var catalog = mockMvc.perform(get("/api/credentials/google-drive").with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        var entry = io.swagger.v3.core.util.Json.mapper().readTree(catalog).findParents("id").stream()
                .filter(node -> node.path("id").asText().equals(credential.value().toString())).findFirst().orElseThrow();
        assertEquals("Reusable owner account", entry.path("name").asText());
        assertEquals("owner@example.com", entry.path("accountEmail").asText());
        assertEquals(2, entry.path("sourceCount").asLong());
        assertTrue(entry.path("oauthClientConfigured").asBoolean());
        Instant.parse(entry.path("createdAt").asText());
        Instant.parse(entry.path("updatedAt").asText());
        assertFalse(catalog.contains("api-refresh-secret"));
        assertFalse(catalog.contains("api-client-secret"));
        assertFalse(catalog.contains("ciphertext"));
        mockMvc.perform(delete("/api/credentials/google-drive/{id}", credential.value())
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/credentials/google-drive/{id}/revoke", credential.value())
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedCredentialRevision\":1}"))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        for (String source : List.of(first, second)) {
            mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.credentialStatus").value("REVOKED"))
                    .andExpect(jsonPath("$.credentialRevision").value(2));
        }
        mockMvc.perform(post("/api/sources/google-drive").with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON)
                        .content(googleSourceBody(credential, "Revoked cannot create", "third-doc")))
                .andExpect(status().isConflict());
    }

    @Test
    void unattachedCredentialDeletionRequiresCsrfAndCurrentRevision() throws Exception {
        var credential = googleCredential("Unused account");
        mockMvc.perform(delete("/api/credentials/google-drive/{id}", credential.value())
                        .with(authentication(owner)).header("If-Match", "\"1\""))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/credentials/google-drive/{id}", credential.value())
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "W/\"1\""))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/credentials/google-drive/{id}", credential.value())
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"2\""))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/credentials/google-drive/{id}", credential.value())
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        mockMvc.perform(get("/api/credentials/google-drive").with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + credential.value() + "')]").isEmpty());
    }

    @Test
    void credentialManagementDoesNotExposeAnOracleToUnprivilegedMembers() throws Exception {
        var credential = googleCredential("Private owner account");
        mockMvc.perform(get("/api/credentials/google-drive").with(authentication(member)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
        mockMvc.perform(get("/api/credentials/google-drive"))
                .andExpect(status().isUnauthorized());
        for (UUID id : List.of(credential.value(), UUID.randomUUID())) {
            mockMvc.perform(delete("/api/credentials/google-drive/{id}", id)
                            .with(authentication(member)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
            mockMvc.perform(post("/api/credentials/google-drive/{id}/revoke", id)
                            .with(authentication(member)).header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedCredentialRevision\":1}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
            mockMvc.perform(post("/api/credentials/google-drive/authorization")
                            .with(authentication(member)).header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Private\",\"credentialId\":\"" + id + "\",\"expectedCredentialRevision\":1}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
            mockMvc.perform(post("/api/sources/google-drive")
                            .with(authentication(member)).header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(googleSourceBody(new CredentialId(id), "Forbidden", "private-doc")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
        }
    }

    @Test
    void scheduleWritesUseIndependentEtagsAndRemainAvailableWithoutAGrant() throws Exception {
        var credential = googleCredential("Scheduled account");
        String source = createGoogleSource(credential, "Scheduled source", "scheduled-doc");
        String other = createGoogleSource(credential, "Independent source", "other-doc");
        mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":17}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.syncIntervalMinutes").value(17))
                .andExpect(jsonPath("$.scheduleRevision").value(2))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.pendingWork").value(true));
        assertSelectedRoot(source, "scheduled-doc");
        mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":30}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_GOOGLE_REVISION_CONFLICT"));
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.syncIntervalMinutes").value(17)).andExpect(jsonPath("$.scheduleRevision").value(2));
        mockMvc.perform(get("/api/sources/{id}/google-drive", other).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.syncIntervalMinutes").value(5))
                .andExpect(jsonPath("$.scheduleRevision").value(1));
        googleAuthorizations.disconnect(owner.getPrincipal().actorId(), credential, 1);
        org.mockito.Mockito.clearInvocations(googleProvider);
        mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":2147483647}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.syncIntervalMinutes").value(Integer.MAX_VALUE))
                .andExpect(jsonPath("$.credentialStatus").value("REVOKED"))
                .andExpect(jsonPath("$.credentialRevision").value(2))
                .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.pendingWork").value(false));
        org.mockito.Mockito.verifyNoInteractions(googleProvider);
    }

    @Test
    void scheduleRequiresAStrongRevisionAndAPositiveIntegerJsonValue() throws Exception {
        String source = createGoogleSource(googleCredential("Validated schedule"), "Validated source", "validated-doc");
        for (String body : List.of("{}", "{\"syncIntervalMinutes\":null}", "{\"syncIntervalMinutes\":0}",
                "{\"syncIntervalMinutes\":-1}", "{\"syncIntervalMinutes\":1.5}", "{\"syncIntervalMinutes\":2147483648}",
                "{\"syncIntervalMinutes\":\"5\"}")) {
            mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                            .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        for (String revision : List.of("1", "W/\"1\"", "*", "\"1\", \"2\"", "\"9223372036854775808\"")) {
            mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                            .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", revision)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":1}"))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.syncIntervalMinutes").value(1))
                .andExpect(jsonPath("$.scheduleRevision").value(2));
    }

    @Test
    void scheduleRequiresAuthenticationOwnerAndSameOriginCsrf() throws Exception {
        String source = createGoogleSource(googleCredential("Private schedule"), "Private source", "private-doc");
        mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                        .header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":15}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                        .with(authentication(member)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":15}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
        mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                        .with(authentication(owner)).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":15}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.syncIntervalMinutes").value(5))
                .andExpect(jsonPath("$.scheduleRevision").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GENERAL", "SPECIFIC"})
    void scopeModeIsCreationOnlyAndRejectedChangesPreserveConfigurationAndSchedule(String savedMode) throws Exception {
        var credential = googleCredential("Immutable scope account");
        boolean general = "GENERAL".equals(savedMode);
        String body = mockMvc.perform(post("/api/sources/google-drive").with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON)
                        .content(general ? selectionRequest("{\"name\":\"My Drive\",\"credentialId\":\"" + credential.value()
                                + "\",\"scopeMode\":\"GENERAL\",\"links\":[]}")
                                : googleSourceBody(credential, "Selected documents", "chosen-doc")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String source = activateSelection(body);
        mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":1}"))
                .andExpect(status().isOk());
        clearInvocations(googleProvider);

        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceRequest(source, general ? "{\"scopeMode\":\"SPECIFIC\",\"links\":[\"https://drive.google.com/file/d/other-doc/view\"],\"linkedDocumentIds\":[]}"
                                : "{\"scopeMode\":\"GENERAL\",\"links\":[],\"linkedDocumentIds\":[]}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SOURCE_INVALID_REQUEST"));

        var unchanged = mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.scopeMode").value(savedMode))
                .andExpect(jsonPath("$.syncIntervalMinutes").value(1)).andExpect(jsonPath("$.scheduleRevision").value(2))
                .andExpect(jsonPath("$.pendingWork").value(true));
        if (general) unchanged.andExpect(jsonPath("$.counts.files").value(0));
        else assertSelectedRoot(source, "chosen-doc");
        verifyNoInteractions(googleProvider);
    }

    @Test
    void specificLinksRemainEditableWithCurrentRevisionAndPreserveTheSchedule() throws Exception {
        String source = createGoogleSource(googleCredential("Editable links"), "Selected documents", "chosen-doc");
        mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":1}"))
                .andExpect(status().isOk());
        String accepted = mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceRequest(source, "{\"scopeMode\":\"SPECIFIC\",\"links\":[\"https://drive.google.com/file/d/other-doc/view\"],\"linkedDocumentIds\":[]}")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertSelectedRoot(source, "chosen-doc");
        activateSelection(accepted);
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.syncIntervalMinutes").value(1))
                .andExpect(jsonPath("$.scheduleRevision").value(2));
        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceRequest(source, "{\"scopeMode\":\"SPECIFIC\",\"links\":[\"https://drive.google.com/file/d/chosen-doc/view\"],\"linkedDocumentIds\":[]}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_GOOGLE_REVISION_CONFLICT"));
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.scopeMode").value("SPECIFIC"));
        assertSelectedRoot(source, "other-doc");
    }

    @Test
    void invalidScopeSelectionsCannotSilentlyWidenAnExistingSource() throws Exception {
        var credential = googleCredential("Validated scope account");
        String source = createGoogleSource(credential, "Selected documents", "retained-doc");
        for (String selection : List.of(
                "\"links\":[]", "\"scopeMode\":0,\"links\":[]", "\"scopeMode\":\"EVERYONE\",\"links\":[]",
                "\"scopeMode\":\"GENERAL\",\"links\":null", "\"scopeMode\":\"SPECIFIC\",\"links\":[]",
                "\"scopeMode\":\"GENERAL\",\"links\":[\"https://drive.google.com/file/d/retained-doc/view\"]")) {
            mockMvc.perform(post("/api/sources/google-drive").with(authentication(owner))
                            .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON)
                            .content(selectionRequest("{\"name\":\"Invalid scope\",\"credentialId\":\"" + credential.value() + "\"," + selection + "}")))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                            .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                            .contentType(MediaType.APPLICATION_JSON).content(replaceRequest(source, "{" + selection + ",\"linkedDocumentIds\":[]}")))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.scopeMode").value("SPECIFIC"));
        assertSelectedRoot(source, "retained-doc");
    }

    @Test
    void scopeReplacementRequiresAuthenticationOwnerAndCsrf() throws Exception {
        String source = createGoogleSource(googleCredential("Private scope"), "Private scope source", "private-doc");
        String selection = replaceRequest(source, "{\"scopeMode\":\"GENERAL\",\"links\":[],\"linkedDocumentIds\":[]}");
        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(selection))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .with(authentication(member)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(selection))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .with(authentication(owner)).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(selection))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scopeMode").value("SPECIFIC"));
        assertSelectedRoot(source, "private-doc");
    }

    @Test
    void linkedDiscoveryReturnsUnselectedCandidatesAndSavesApprovalAtomicallyWithRoots() throws Exception {
        String source = createGoogleSource(googleCredential("Linked documents"), "Linked source", "linked-root");
        when(googleSession.acquire(any())).thenAnswer(invocation -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            GoogleDriveProvider.FileMetadata file = invocation.getArgument(0);
            return new GoogleDriveProvider.AcquiredContent(file.name() + ".txt", "text/plain",
                    "References https://drive.google.com/file/d/linked-target/view".getBytes(UTF_8),
                    new SourceInputDescriptor(SourceInputFormat.BINARY, file.id(), file.version(),
                            "https://drive.google.com/file/d/" + file.id() + "/view"));
        });
        mockMvc.perform(post("/api/sources/{id}/google-drive/linked-documents/discover", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.discoveryRevision").value(1)).andExpect(jsonPath("$.discoveredAt").isString())
                .andExpect(jsonPath("$.discoveryErrors").isEmpty());
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection?kind=LINKED", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("linked-target"))
                .andExpect(jsonPath("$.items[0].selected").value(false))
                .andExpect(jsonPath("$.items[0].coveredByRoots").value(false))
                .andExpect(jsonPath("$.items[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.items[0].origins[0].rootId").value("linked-root"))
                .andExpect(jsonPath("$.items[0].origins[0].parentId").value("linked-root"));
        org.mockito.Mockito.verify(googleSession, org.mockito.Mockito.never()).acquire(org.mockito.ArgumentMatchers.argThat(
                file -> file.id().equals("linked-target")));
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value("linked-root"))
                .andExpect(jsonPath("$.items[0].expandable").value(true));
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", source).with(authentication(owner))
                        .param("parentId", "linked-root"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("linked-target"))
                .andExpect(jsonPath("$.items[0].kind").value("LINKED"))
                .andExpect(jsonPath("$.items[0].selected").value(false))
                .andExpect(jsonPath("$.items[0].expandable").value(false))
                .andExpect(jsonPath("$.items[0].origins[0].parentId").value("linked-root"));
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", source).with(authentication(owner))
                        .param("parentId", "linked-target"))
                .andExpect(status().isNotFound());
        String accepted = mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceRequest(source, "{\"scopeMode\":\"SPECIFIC\",\"links\":[\"https://drive.google.com/file/d/linked-root/view\"],\"linkedDocumentIds\":[\"linked-target\"]}")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        activateSelection(accepted);
        assertSelectedRoot(source, "linked-root");
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection?kind=LINKED", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].selected").value(true));
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", source).with(authentication(owner))
                        .param("parentId", "linked-root"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].selected").value(true));
        mockMvc.perform(post("/api/sources/{id}/google-drive/linked-documents/discover", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_GOOGLE_REVISION_CONFLICT"));
    }

    @Test
    void linkedDiscoveryAndSelectionRejectMissingPreconditionsUnauthorizedAndUnknownTargets() throws Exception {
        var credential = googleCredential("Private linked source");
        String source = createGoogleSource(credential, "Private linked source", "linked-root");
        clearInvocations(googleProvider);
        mockMvc.perform(post("/api/sources/{id}/google-drive/linked-documents/discover", source)
                        .header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/sources/{id}/google-drive/linked-documents/discover", source)
                        .with(authentication(owner)).header("If-Match", "\"1\""))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/sources/{id}/google-drive/linked-documents/discover", source)
                        .with(authentication(member)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
        for (String revision : List.of("1", "W/\"1\"", "*", "\"1\", \"2\"")) {
            mockMvc.perform(post("/api/sources/{id}/google-drive/linked-documents/discover", source)
                            .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", revision))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/sources/{id}/google-drive/linked-documents/discover", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isBadRequest());
        for (String ids : List.of("", ",\"linkedDocumentIds\":null", ",\"linkedDocumentIds\":[\"arbitrary\"]",
                ",\"linkedDocumentIds\":[\"duplicate\",\"duplicate\"]")) {
            mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                            .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(replaceRequest(source, "{\"scopeMode\":\"SPECIFIC\",\"links\":[\"https://drive.google.com/file/d/linked-root/view\"]" + ids + "}")))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(googleProvider);
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.discoveryRevision").value(0)).andExpect(jsonPath("$.counts.linkedDocuments").value(0));
        googleAuthorizations.disconnect(owner.getPrincipal().actorId(), credential, 1);
        mockMvc.perform(post("/api/sources/{id}/google-drive/linked-documents/discover", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                .andExpect(status().isConflict());
    }

    @Test
    void generalCannotDiscoverOrApproveAndDiscoveryFailureDoesNotPretendToBeComplete() throws Exception {
        var credential = googleCredential("Discovery failures");
        String generalBody = mockMvc.perform(post("/api/sources/google-drive").with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON)
                        .content(selectionRequest("{\"name\":\"General\",\"credentialId\":\"" + credential.value() + "\",\"scopeMode\":\"GENERAL\",\"links\":[]}")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String general = activateSelection(generalBody);
        mockMvc.perform(post("/api/sources/{id}/google-drive/linked-documents/discover", general)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/sources/{id}/google-drive", general).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.discoveryRevision").value(0))
                .andExpect(jsonPath("$.discoveredAt").isEmpty()).andExpect(jsonPath("$.counts.linkedDocuments").value(0))
                .andExpect(jsonPath("$.discoveryErrors").isEmpty());
        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", general)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(replaceRequest(general, "{\"scopeMode\":\"GENERAL\",\"links\":[],\"linkedDocumentIds\":[\"arbitrary\"]}")))
                .andExpect(status().isBadRequest());

        String specific = createGoogleSource(credential, "Specific", "linked-root");
        when(googleSession.acquire(any())).thenThrow(new GoogleDriveProviderException(GoogleDriveProviderException.Failure.LIMIT_EXCEEDED));
        mockMvc.perform(post("/api/sources/{id}/google-drive/linked-documents/discover", specific)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GOOGLE_DRIVE_LIMIT_EXCEEDED"));
        mockMvc.perform(get("/api/sources/{id}/google-drive", specific).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.discoveryRevision").value(0))
                .andExpect(jsonPath("$.discoveredAt").isEmpty()).andExpect(jsonPath("$.counts.linkedDocuments").value(0));
    }

    @Test
    void selectionTreeExpandsActualFoldersWithoutLinksAndKeepsOwnerAndProviderErrorBoundaries() throws Exception {
        var credential = googleCredential("Tree account");
        var folder = new GoogleDriveProvider.FileMetadata("tree-folder", "Selected folder", "application/vnd.google-apps.folder",
                "1", null, null, false, List.of("my-drive-root"), null, null);
        var nested = new GoogleDriveProvider.FileMetadata("tree-nested", "Nested folder", "application/vnd.google-apps.folder",
                "1", null, null, false, List.of("tree-folder"), null, null);
        var file = new GoogleDriveProvider.FileMetadata("tree-file", "A file without links", "text/plain",
                "1", null, null, false, List.of("tree-nested"), null, null);
        when(googleSession.metadata("tree-folder")).thenReturn(folder);
        when(googleSession.metadata("tree-nested")).thenReturn(nested);
        when(googleSession.metadata("tree-file")).thenReturn(file);
        when(googleSession.listFiles("tree-folder", null)).thenReturn(new GoogleDriveProvider.FilePage(List.of(nested), null));
        when(googleSession.listFiles("tree-nested", null)).thenAnswer(_ -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return new GoogleDriveProvider.FilePage(List.of(file), null);
        });
        String source = createGoogleSource(credential, "Nested tree", "tree-folder");
        clearInvocations(googleSession, googleProvider);
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", source))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", source).with(authentication(member)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items[0].id").value("tree-folder"))
                .andExpect(jsonPath("$.items[0].expandable").value(true))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        verifyNoInteractions(googleSession, googleProvider);
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", source).with(authentication(owner))
                        .param("parentId", "tree-folder"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("tree-nested"))
                .andExpect(jsonPath("$.items[0].expandable").value(true));
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", source).with(authentication(owner))
                        .param("parentId", "tree-nested"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("tree-file"))
                .andExpect(jsonPath("$.items[0].kind").value("FILE"))
                .andExpect(jsonPath("$.items[0].coveredByRoots").value(true))
                .andExpect(jsonPath("$.items[0].expandable").value(false));
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", source).with(authentication(owner))
                        .param("parentId", "tree-file"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        org.mockito.Mockito.verify(googleSession, org.mockito.Mockito.never()).acquire(any());
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", source).with(authentication(owner)).param("size", "101"))
                .andExpect(status().isBadRequest());
        when(googleSession.listFiles("tree-folder", null)).thenThrow(new GoogleDriveProviderException(GoogleDriveProviderException.Failure.QUOTA));
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection-tree", source).with(authentication(owner))
                        .param("parentId", "tree-folder"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("GOOGLE_DRIVE_QUOTA"));
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.discoveryRevision").value(0));
    }

    private CredentialId googleCredential(String name) {
        var scopes = new HashSet<>(GoogleDriveAuthorizationService.REQUIRED_SCOPES);
        scopes.add("email");
        var providerSession = mock(GoogleDriveProvider.Session.class);
        googleSession = providerSession;
        when(googleProvider.open(any())).thenReturn(providerSession);
        when(providerSession.metadata(anyString())).thenAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return "root".equals(id)
                    ? new GoogleDriveProvider.FileMetadata("my-drive-root", "My Drive", "application/vnd.google-apps.folder",
                            "1", null, null, false, List.of(), null, null)
                    : new GoogleDriveProvider.FileMetadata(id, "Document", "text/plain", "1", null, null, false, List.of(), null, null);
        });
        try (var client = new GoogleDriveOAuthClient("api-client.apps.googleusercontent.com", "api-client-secret".getBytes(UTF_8));
                var grant = new GoogleDriveAuthorizationService.Grant("google-api-owner", "owner@example.com",
                        scopes, "api-refresh-secret".getBytes(UTF_8))) {
            var preparation = googleAuthorizations.prepare(owner.getPrincipal().actorId(), name, null, null, client);
            return googleAuthorizations.complete(owner.getPrincipal().actorId(), preparation, grant);
        }
    }

    private String createGoogleSource(CredentialId credential, String name, String root) throws Exception {
        String body = mockMvc.perform(post("/api/sources/google-drive").with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON)
                        .content(googleSourceBody(credential, name, root)))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        return activateSelection(body);
    }

    private static String googleSourceBody(CredentialId credential, String name, String root) {
        return selectionRequest("{\"name\":\"" + name + "\",\"credentialId\":\"" + credential.value()
                + "\",\"scopeMode\":\"SPECIFIC\",\"links\":[\"https://drive.google.com/file/d/" + root + "/view\"]}");
    }

    private static String selectionRequest(String body) {
        return "{\"requestId\":\"" + UUID.randomUUID() + "\"," + body.substring(1);
    }

    private String replaceRequest(String source, String body) throws Exception {
        String response = mockMvc.perform(get("/api/sources/{id}/google-drive/selection-draft", source)
                        .with(authentication(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var draft = io.swagger.v3.core.util.Json.mapper().readTree(response);
        return selectionRequest("{\"discoveryRevision\":" + draft.path("discoveryRevision").asLong()
                + ",\"credentialRevision\":" + draft.path("credentialRevision").asLong() + "," + body.substring(1));
    }

    private String activateSelection(String receiptBody) throws Exception {
        var receipt = io.swagger.v3.core.util.Json.mapper().readTree(receiptBody);
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try (var scheduler = Executors.newSingleThreadScheduledExecutor()) {
            var processor = new io.memoryos.ingestion.application.SelectionValidationProcessor(selections, scheduler, metrics);
            for (int batch = 0; batch < 256; batch++) {
                var claims = operationDispatch.claim(OperationWorkload.GOOGLE_DRIVE_SELECTION_VALIDATION, 8);
                if (claims.isEmpty()) break;
                claims.forEach(claim -> processor.process(claim.delivery()));
            }
        } finally {
            metrics.close();
        }
        mockMvc.perform(get("/api/source-operations/{id}", receipt.path("operation").path("id").asText())
                        .with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        return receipt.path("sourceId").asText();
    }

    private void assertSelectedRoot(String source, String root) throws Exception {
        mockMvc.perform(get("/api/sources/{id}/google-drive/selection?kind=FILE", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(root))
                .andExpect(jsonPath("$.items[0].selected").value(true));
    }

    @Test
    void enforcesScopedSourceHttpSurfacesAndImmediateAssociationRevocation() throws Exception {
        UUID tenantId = jdbcClient.sql("SELECT id FROM tenants WHERE slug = 'sources'")
                .query(UUID.class)
                .single();
        UUID managedGroupId = UUID.randomUUID();
        ActorAuthenticationToken manager = scopedManager(tenantId, managedGroupId);
        String managedSourceId = createSource(owner, "Manager source", managedGroupId);
        String hiddenSourceId = createSource(owner, "Hidden manager source", null);
        ApiUpload managedUpload = uploadAndFinalize(
                manager,
                managedSourceId,
                "manager.txt",
                "manager-visible content".getBytes(UTF_8)
        );
        ApiUpload hiddenUpload = uploadAndFinalize(
                owner,
                hiddenSourceId,
                "hidden.txt",
                "hidden content".getBytes(UTF_8)
        );

        mockMvc.perform(get("/api/sources").with(authentication(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(managedSourceId)).exists())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(hiddenSourceId)).doesNotExist());
        mockMvc.perform(get("/api/sources/{sourceId}", managedSourceId).with(authentication(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0]").value("upload"))
                .andExpect(jsonPath("$.actions[1]").value("reindex"))
                .andExpect(jsonPath("$.actions.length()").value(2));
        mockMvc.perform(get("/api/sources/{sourceId}", hiddenSourceId).with(authentication(manager)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/sources/{sourceId}/groups", managedSourceId)
                        .with(authentication(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(managedGroupId.toString()));
        mockMvc.perform(get("/api/groups/{groupId}/sources", managedGroupId)
                        .with(authentication(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(managedSourceId));
        mockMvc.perform(get("/api/groups/{groupId}/sources", adminGroupId())
                        .with(authentication(manager)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/source-operations/{operationId}", managedUpload.operationId())
                        .with(authentication(manager)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/source-operations/{operationId}", hiddenUpload.operationId())
                        .with(authentication(manager)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));

        mockMvc.perform(post(
                        "/api/sources/{sourceId}/items/{itemId}/index-attempts",
                        managedSourceId,
                        managedUpload.itemId()
                )
                        .with(authentication(manager))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/sources/{sourceId}/uploads", hiddenSourceId)
                        .with(authentication(manager))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"filename":"denied.txt","mediaType":"text/plain","sizeBytes":1,
                                 "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_FOUND"));
        mockMvc.perform(post(
                        "/api/sources/{sourceId}/items/{itemId}/remove",
                        managedSourceId,
                        managedUpload.itemId()
                )
                        .with(authentication(manager))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
        mockMvc.perform(post("/api/sources/{sourceId}/delete", managedSourceId)
                        .with(authentication(manager))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
        mockMvc.perform(post("/api/sources/file")
                        .with(authentication(manager))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Denied manager create\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
        mockMvc.perform(post("/api/sources/{sourceId}/groups", managedSourceId)
                        .with(authentication(manager))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupIds\":[\"%s\"]}".formatted(managedGroupId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
        mockMvc.perform(get("/api/sources/group-options").with(authentication(manager)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));

        mockMvc.perform(get("/api/sources/group-options?search=Scoped")
                        .with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(managedGroupId.toString()));
        mockMvc.perform(post("/api/sources/{sourceId}/groups", managedSourceId)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION"));
        mockMvc.perform(post("/api/sources/{sourceId}/groups", managedSourceId)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupIds\":[\"%s\"]}".formatted(adminGroupId())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/sources/{sourceId}", managedSourceId).with(authentication(manager)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/source-operations/{operationId}", managedUpload.operationId())
                        .with(authentication(manager)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/sources").with(authentication(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(managedSourceId)).doesNotExist());
    }

    @Test
    void rejectsMemberManagementAndBothValidationFailureShapes() throws Exception {
        mockMvc.perform(post("/api/sources/file")
                        .with(authentication(member))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Forbidden\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));

        mockMvc.perform(post("/api/sources/file")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));

        String sourceBody = mockMvc.perform(post("/api/sources/file")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Validation source\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sourceId = Json.mapper().readTree(sourceBody)
                .path("id").textValue();
        mockMvc.perform(get("/api/sources/{sourceId}/index-attempts?size=0", sourceId)
                        .with(authentication(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION"));
        mockMvc.perform(get("/api/sources/{sourceId}/items", sourceId).with(authentication(member)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IAM_ACCESS_DENIED"));
        mockMvc.perform(get("/api/sources/{sourceId}/items", sourceId).with(authentication(owner)).param("size", "0"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_VALIDATION"));
        mockMvc.perform(get("/api/sources/{sourceId}/items", sourceId).with(authentication(owner)).param("size", "101"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REQUEST_VALIDATION"));
        mockMvc.perform(get("/api/sources/{sourceId}/items", sourceId).with(authentication(owner)).param("cursor", "!"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SOURCE_INVALID_REQUEST"));

        mockMvc.perform(post("/api/sources/file")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/sources/{sourceId}/uploads", sourceId)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"filename":"scanned.pdf","mediaType":"application/pdf",
                                 "sizeBytes":104857600,
                                 "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("PUT"));
        mockMvc.perform(post("/api/sources/{sourceId}/uploads", sourceId)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"filename":"oversized.txt","mediaType":"text/plain",
                                 "sizeBytes":104857601,
                                 "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION"));

    }

    @Test
    void searchUsesExistingSessionGuardAndSafeUnavailableContract() throws Exception {
        String query = "{\"query\":\"nghỉ phép\",\"page\":0,\"pageSize\":10}";
        mockMvc.perform(post("/api/search").with(authentication(member)).contentType(MediaType.APPLICATION_JSON).content(query))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/search").with(authentication(member)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(query))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"));
        mockMvc.perform(post("/api/search").with(authentication(member)).header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"\",\"page\":0,\"pageSize\":10}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/search/documents/{id}", UUID.randomUUID()).with(authentication(member))
                        .param("generation", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SEARCH_DOCUMENT_UNAVAILABLE"));
    }

    private ActorAuthenticationToken scopedManager(UUID tenantId, UUID groupId) {
        UUID actorId = UUID.randomUUID();
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:actorId)")
                .param("actorId", actorId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO tenant_memberships (tenant_id, actor_id, role, status)
                        VALUES (:tenantId, :actorId, 'MEMBER', 'ACTIVE')
                        """)
                .param("tenantId", tenantId)
                .param("actorId", actorId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO iam_groups (tenant_id, id, name)
                        VALUES (:tenantId, :groupId, :name)
                        """)
                .param("tenantId", tenantId)
                .param("groupId", groupId)
                .param("name", "Scoped " + groupId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO iam_group_memberships (
                            tenant_id, group_id, actor_id, is_manager
                        ) VALUES (
                            :tenantId, :basicGroupId, :actorId, FALSE
                        ), (
                            :tenantId, :groupId, :actorId, TRUE
                        )
                        """)
                .param("tenantId", tenantId)
                .param("basicGroupId", UUID.fromString("00000000-0000-0000-0000-000000000002"))
                .param("groupId", groupId)
                .param("actorId", actorId)
                .update();
        return token(actorId);
    }

    private String createSource(
            ActorAuthenticationToken actor,
            String name,
            @Nullable UUID groupId
    ) throws Exception {
        String request = groupId == null
                ? "{\"name\":\"%s\"}".formatted(name)
                : "{\"name\":\"%s\",\"groupIds\":[\"%s\"]}".formatted(name, groupId);
        String response = mockMvc.perform(post("/api/sources/file")
                        .with(authentication(actor))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Json.mapper().readTree(response)
                .path("id").textValue();
    }

    private ApiUpload uploadAndFinalize(
            ActorAuthenticationToken actor,
            String sourceId,
            String filename,
            byte[] content
    ) throws Exception {
        String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String authorizationBody = mockMvc.perform(post("/api/sources/{sourceId}/uploads", sourceId)
                        .with(authentication(actor))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"filename":"%s","mediaType":"text/plain","sizeBytes":%d,"sha256":"%s"}
                                """.formatted(filename, content.length, checksum)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var authorization = Json.mapper().readTree(authorizationBody);
        String uploadId = authorization.path("uploadId").textValue();
        objectStorage.put(URI.create(authorization.path("uploadUrl").textValue()), content);
        String receiptBody = mockMvc.perform(post(
                        "/api/sources/{sourceId}/uploads/{uploadId}/finalize",
                        sourceId,
                        uploadId
                )
                        .with(authentication(actor))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        var receipt = Json.mapper().readTree(receiptBody);
        return new ApiUpload(
                receipt.path("item").path("id").textValue(),
                receipt.path("operation").path("id").textValue()
        );
    }

    private static UUID adminGroupId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000001");
    }

    private record ApiUpload(String itemId, String operationId) {
    }

    private void processDispatchedWork() {
        var metrics = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try (var leaseScheduler = Executors.newSingleThreadScheduledExecutor()) {
            var coordinator = new DefaultIngestionCoordinator(
                    indexingPort,
                    cleanupPort,
                    documents,
                    extractor,
                    objectStorage,
                    storedObjects,
                    new TransactionTemplate(transactionManager),
                    leaseScheduler,
                    extractionArtifacts,
                    metrics,
                    new io.memoryos.ingestion.application.SourceSyncProcessor(sourceSync, leaseScheduler, metrics),
                    new io.memoryos.ingestion.application.SelectionValidationProcessor(selections, leaseScheduler, metrics)
            );
            for (OperationWorkload workload : List.of(OperationWorkload.INGESTION, OperationWorkload.CLEANUP)) {
                operationDispatch.claim(workload, 8)
                        .forEach(claim -> coordinator.process(claim.delivery()));
            }
        } finally {
            metrics.close();
        }
    }

    private UUID ownerActorId() {
        return jdbcClient.sql("""
                        SELECT actor_id FROM external_identity_bindings
                        WHERE issuer = :issuer AND subject = 'source-owner'
                        """)
                .param("issuer", "https://issuer.example.test")
                .query(UUID.class)
                .single();
    }

    private static ActorAuthenticationToken token(UUID actorId) {
        return new ActorAuthenticationToken(new IdentityContext(new ActorId(actorId)));
    }

    private static HttpServer startIdentityServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/.well-known/openid-configuration", exchange -> {
                String issuer = "http://127.0.0.1:" + server.getAddress().getPort();
                byte[] body = """
                        {
                          "issuer": "%s",
                          "authorization_endpoint": "%s/authorize",
                          "token_endpoint": "%s/token",
                          "jwks_uri": "%s/jwks",
                          "userinfo_endpoint": "%s/userinfo",
                          "subject_types_supported": ["public"],
                          "id_token_signing_alg_values_supported": ["RS256"]
                        }
                        """.formatted(issuer, issuer, issuer, issuer, issuer).getBytes(UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(body);
                }
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not start local identity server", exception);
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class StorageTestConfiguration {
        @Bean
        @Primary
        InMemoryObjectStorage testObjectStorage() {
            return new InMemoryObjectStorage();
        }
    }

    static final class InMemoryObjectStorage implements ObjectStorage {
        @Override
        public void write(ObjectKey key, byte[] content, String mediaType) {
            var authorization = authorizeUpload(key, new UploadConstraints(content.length, mediaType, checksum(content)));
            put(authorization.uri(), content);
        }
        private final AtomicLong sequence = new AtomicLong();
        private final Map<URI, Entry> authorizations = new ConcurrentHashMap<>();
        private final Map<ObjectKey, Entry> objects = new ConcurrentHashMap<>();

        @Override
        public UploadAuthorization authorizeUpload(ObjectKey key, UploadConstraints constraints) {
            URI uri = URI.create("https://uploads.example.test/" + sequence.incrementAndGet());
            Entry entry = new Entry(constraints);
            authorizations.put(uri, entry);
            objects.put(key, entry);
            return new UploadAuthorization(
                    "PUT",
                    uri,
                    Map.of(
                            "content-type", constraints.mediaType(),
                            "x-amz-checksum-sha256", constraints.checksum().base64()
                    ),
                    Instant.now().plusSeconds(600)
            );
        }

        void put(URI uri, byte[] content) {
            Entry entry = authorizations.get(uri);
            if (entry == null) {
                throw new IllegalArgumentException("unknown upload authorization");
            }
            ContentSha256 checksum = checksum(content);
            if (content.length != entry.constraints.sizeBytes()
                    || !checksum.equals(entry.constraints.checksum())) {
                throw new IllegalArgumentException("uploaded bytes do not match authorization");
            }
            entry.content = content.clone();
        }

        @Override
        public ObjectMetadata inspect(ObjectKey key) {
            Entry entry = requireEntry(key);
            return new ObjectMetadata(
                    entry.content.length,
                    entry.constraints.mediaType(),
                    checksum(entry.content)
            );
        }

        @Override
        public ObjectContent open(ObjectKey key) {
            Entry entry = requireEntry(key);
            ObjectMetadata metadata = inspect(key);
            ByteArrayInputStream input = new ByteArrayInputStream(entry.content.clone());
            return new ObjectContent() {
                @Override
                public ObjectMetadata metadata() {
                    return metadata;
                }

                @Override
                public ByteArrayInputStream inputStream() {
                    return input;
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public void delete(ObjectKey key) {
            objects.remove(key);
        }

        private Entry requireEntry(ObjectKey key) {
            Entry entry = objects.get(key);
            if (entry == null || entry.content == null) {
                throw new ObjectStorageException(ObjectStorageFailureCode.NOT_FOUND, false, null);
            }
            return entry;
        }

        private static ContentSha256 checksum(byte[] content) {
            try {
                return new ContentSha256(HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(content)
                ));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static final class Entry {
            private final UploadConstraints constraints;
            private volatile byte[] content;

            private Entry(UploadConstraints constraints) {
                this.constraints = constraints;
            }
        }
    }
}
