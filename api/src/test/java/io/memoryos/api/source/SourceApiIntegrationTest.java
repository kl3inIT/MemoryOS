package io.memoryos.api.source;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import io.memoryos.connector.ConnectorCleanupPort;
import io.memoryos.connector.CredentialId;
import io.memoryos.connector.GoogleDriveAuthorizationService;
import io.memoryos.connector.GoogleDriveOAuthClient;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.SourceDocumentAccessResolver;
import io.memoryos.document.DocumentId;
import io.memoryos.connector.ConnectorIndexingPort;
import io.memoryos.api.security.ActorAuthenticationToken;
import io.memoryos.document.DocumentCommandPort;
import io.memoryos.identity.ActorId;
import io.memoryos.identity.IdentityContext;
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

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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
        "memoryos.initial-tenant.change-reference=MEM-35-TEST"
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
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
            .asCompatibleSubstituteFor("postgres"));

    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER =
            "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

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
    private io.memoryos.document.ExtractionArtifactPort extractionArtifacts;

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

    private ActorAuthenticationToken owner;
    private ActorAuthenticationToken member;

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
    void indexesAndCleansUpOneFileThroughTheAuthorizedApi() throws Exception {
        String sourceBody = mockMvc.perform(post("/api/sources/file")
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Product documentation\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source.status").value("NOT_STARTED"))
                .andReturn().getResponse().getContentAsString();
        String sourceId = io.swagger.v3.core.util.Json.mapper().readTree(sourceBody)
                .path("source").path("id").textValue();

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
        var authorization = io.swagger.v3.core.util.Json.mapper().readTree(authorizationBody);
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
        String itemId = io.swagger.v3.core.util.Json.mapper().readTree(uploadBody)
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
                .andExpect(jsonPath("$.source.status").value("ACTIVE"))
                .andExpect(jsonPath("$.source.documentCount").value(1))
                .andExpect(jsonPath("$.items[0].status").value("INDEXED"));

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
                .andExpect(jsonPath("$.source.pendingWork").value(true))
                .andExpect(jsonPath("$.items[0].status").value("DELETING"));
        processDispatchedWork();
        mockMvc.perform(get("/api/sources/{sourceId}", sourceId).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source.pendingWork").value(false))
                .andExpect(jsonPath("$.items").isEmpty());

        String deleteBody = mockMvc.perform(post("/api/sources/{sourceId}/delete", sourceId)
                        .with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.type").value("DELETE_SOURCE"))
                .andReturn().getResponse().getContentAsString();
        String deleteOperationId = io.swagger.v3.core.util.Json.mapper().readTree(deleteBody)
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
    void googleAuthorizationKeepsOwnerAndCsrfChecksBeforeRequiringOwnerApp() throws Exception {
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
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_OWNER"));
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
        var otherOwner = token(actorId);
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
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scopeMode\":\"GENERAL\",\"links\":[]}"))
                .andExpect(status().isNotFound());
        jdbcClient.sql("UPDATE tenant_memberships SET status = 'INACTIVE' WHERE tenant_id = :tenant AND actor_id = :actor")
                .param("tenant", tenantId).param("actor", actorId).update();
        mockMvc.perform(get("/api/credentials/google-drive").with(authentication(otherOwner)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SOURCE_NOT_OWNER"));
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
                    .andExpect(jsonPath("$.scopeMode").value("SPECIFIC"))
                    .andExpect(jsonPath("$.roots[0].id").value(source.equals(first) ? "first-doc" : "second-doc"));
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
    void credentialManagementDoesNotExposeAnOracleToNonOwners() throws Exception {
        var credential = googleCredential("Private owner account");
        mockMvc.perform(get("/api/credentials/google-drive").with(authentication(member)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SOURCE_NOT_OWNER"));
        mockMvc.perform(get("/api/credentials/google-drive"))
                .andExpect(status().isUnauthorized());
        for (UUID id : List.of(credential.value(), UUID.randomUUID())) {
            mockMvc.perform(delete("/api/credentials/google-drive/{id}", id)
                            .with(authentication(member)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\""))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SOURCE_NOT_OWNER"));
            mockMvc.perform(post("/api/credentials/google-drive/{id}/revoke", id)
                            .with(authentication(member)).header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedCredentialRevision\":1}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SOURCE_NOT_OWNER"));
            mockMvc.perform(post("/api/credentials/google-drive/authorization")
                            .with(authentication(member)).header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Private\",\"credentialId\":\"" + id + "\",\"expectedCredentialRevision\":1}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SOURCE_NOT_OWNER"));
            mockMvc.perform(post("/api/sources/google-drive")
                            .with(authentication(member)).header("X-MemoryOS-CSRF", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(googleSourceBody(new CredentialId(id), "Forbidden", "private-doc")))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SOURCE_NOT_OWNER"));
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
                .andExpect(jsonPath("$.pendingWork").value(true))
                .andExpect(jsonPath("$.roots[0].id").value("scheduled-doc"));
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
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SOURCE_NOT_OWNER"));
        mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                        .with(authentication(owner)).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":15}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.syncIntervalMinutes").value(5))
                .andExpect(jsonPath("$.scheduleRevision").value(1));
    }

    @Test
    void scopeSwitchesPersistWithCurrentRevisionWithoutChangingTheSchedule() throws Exception {
        var credential = googleCredential("General scope account");
        String body = mockMvc.perform(post("/api/sources/google-drive").with(authentication(owner))
                        .header("X-MemoryOS-CSRF", "1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My Drive\",\"credentialId\":\"" + credential.value()
                                + "\",\"scopeMode\":\"GENERAL\",\"links\":[]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String source = io.swagger.v3.core.util.Json.mapper().readTree(body).path("source").path("id").asText();
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.scopeMode").value("GENERAL")).andExpect(jsonPath("$.roots").isEmpty());
        mockMvc.perform(put("/api/sources/{id}/google-drive/schedule", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"syncIntervalMinutes\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeMode\":\"SPECIFIC\",\"links\":[\"https://drive.google.com/file/d/chosen-doc/view\"]}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.scopeMode").value("SPECIFIC"))
                .andExpect(jsonPath("$.roots[0].id").value("chosen-doc"))
                .andExpect(jsonPath("$.syncIntervalMinutes").value(1))
                .andExpect(jsonPath("$.scheduleRevision").value(2));
        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scopeMode\":\"GENERAL\",\"links\":[]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOURCE_GOOGLE_REVISION_CONFLICT"));
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scopeMode").value("SPECIFIC"))
                .andExpect(jsonPath("$.roots[0].id").value("chosen-doc"));
        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scopeMode\":\"GENERAL\",\"links\":[]}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""));
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scopeMode").value("GENERAL"))
                .andExpect(jsonPath("$.roots").isEmpty())
                .andExpect(jsonPath("$.syncIntervalMinutes").value(1)).andExpect(jsonPath("$.scheduleRevision").value(2));
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
                            .content("{\"name\":\"Invalid scope\",\"credentialId\":\"" + credential.value() + "\"," + selection + "}"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                            .with(authentication(owner)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                            .contentType(MediaType.APPLICATION_JSON).content("{" + selection + "}"))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.scopeMode").value("SPECIFIC"))
                .andExpect(jsonPath("$.roots[0].id").value("retained-doc"));
    }

    @Test
    void broadeningScopeStillRequiresAuthenticationOwnerAndCsrf() throws Exception {
        String source = createGoogleSource(googleCredential("Private scope"), "Private scope source", "private-doc");
        String selection = "{\"scopeMode\":\"GENERAL\",\"links\":[]}";
        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(selection))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .with(authentication(member)).header("X-MemoryOS-CSRF", "1").header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(selection))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SOURCE_NOT_OWNER"));
        mockMvc.perform(put("/api/sources/{id}/google-drive/roots", source)
                        .with(authentication(owner)).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(selection))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/sources/{id}/google-drive", source).with(authentication(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.scopeMode").value("SPECIFIC"))
                .andExpect(jsonPath("$.roots[0].id").value("private-doc"));
    }

    private CredentialId googleCredential(String name) {
        var scopes = new HashSet<>(GoogleDriveAuthorizationService.REQUIRED_SCOPES);
        scopes.add("email");
        var providerSession = mock(GoogleDriveProvider.Session.class);
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
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        return io.swagger.v3.core.util.Json.mapper().readTree(body).path("source").path("id").asText();
    }

    private static String googleSourceBody(CredentialId credential, String name, String root) {
        return "{\"name\":\"" + name + "\",\"credentialId\":\"" + credential.value()
                + "\",\"scopeMode\":\"SPECIFIC\",\"links\":[\"https://drive.google.com/file/d/" + root + "/view\"]}";
    }

    @Test
    void rejectsMemberManagementAndBothValidationFailureShapes() throws Exception {
        mockMvc.perform(post("/api/sources/file")
                        .with(authentication(member))
                        .header("X-MemoryOS-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Forbidden\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_OWNER"));

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
        String sourceId = io.swagger.v3.core.util.Json.mapper().readTree(sourceBody)
                .path("source").path("id").textValue();
        mockMvc.perform(get("/api/sources/{sourceId}/index-attempts?size=0", sourceId)
                        .with(authentication(owner)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION"));

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
                                {"filename":"oversized.txt","mediaType":"text/plain",
                                 "sizeBytes":10485761,
                                 "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION"));

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
                    new io.memoryos.ingestion.application.SourceSyncProcessor(sourceSync, leaseScheduler, metrics)
            );
            for (OperationWorkload workload : OperationWorkload.values()) {
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
            } catch (java.security.NoSuchAlgorithmException exception) {
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
