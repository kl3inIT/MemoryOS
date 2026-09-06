package io.memoryos.api.source;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.connector.ConnectorCleanupPort;
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
import java.util.HexFormat;
import java.util.Map;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
        "memoryos.identity.audience=memoryos-api",
        "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
        "arconia.multitenancy.resolution.fixed.tenant-identifier=10000000-0000-0000-0000-000000000024",
        "memoryos.initial-tenant.id=10000000-0000-0000-0000-000000000024",
        "memoryos.initial-tenant.owner-subject=source-owner",
        "memoryos.initial-tenant.slug=sources",
        "memoryos.initial-tenant.display-name=Sources",
        "memoryos.initial-tenant.change-reference=MEM-35-TEST",
        "spring.datasource.url=jdbc:h2:mem:source-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
@AutoConfigureMockMvc
@Import(SourceApiIntegrationTest.StorageTestConfiguration.class)
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
    private io.memoryos.document.ExtractionArtifactPort extractionArtifacts;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private InMemoryObjectStorage objectStorage;

    @Autowired
    private StoredObjectRegistry storedObjects;

    private ActorAuthenticationToken owner;
    private ActorAuthenticationToken member;

    @DynamicPropertySource
    static void browserProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.memoryos.issuer-uri", () -> BROWSER_ISSUER);
        registry.add("memoryos.identity.keycloak.admin.server-url", () -> "http://127.0.0.1:1");
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
        processDispatchedWork();
        mockMvc.perform(get("/api/sources/{sourceId}", sourceId).with(authentication(owner)))
                .andExpect(status().isOk())
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").doesNotExist());
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
                    extractionArtifacts
            );
            for (OperationWorkload workload : OperationWorkload.values()) {
                operationDispatch.claim(workload, 8)
                        .forEach(claim -> coordinator.process(claim.delivery()));
            }
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
