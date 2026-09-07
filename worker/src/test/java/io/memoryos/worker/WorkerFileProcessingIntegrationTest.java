package io.memoryos.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.memoryos.connector.SourceManagementService;
import io.memoryos.connector.SourceStatus;
import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectUploadSpecification;
import io.memoryos.iam.ActorId;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "management.otlp.metrics.export.enabled=true",
                "management.otlp.metrics.export.step=1s",
                "management.otlp.metrics.export.aggregation-temporality=cumulative",
                "management.otlp.metrics.export.resource-attributes.service.name=memoryos-worker",
                "db-scheduler.enabled=true",
                "db-scheduler.scheduler-name=redis-cutover-integration",
                "db-scheduler.polling-interval=50ms",
                "management.endpoint.health.group.readiness.include=readinessState,db,redis,dbScheduler",
                "memoryos.worker.enabled=true",
                "memoryos.redis.relay-interval=50ms",
                "memoryos.redis.rediscovery-delay=750ms",
                "memoryos.redis.transport-backoff=100ms",
                "memoryos.redis.consumer-block=100ms",
                "memoryos.redis.reclaim-interval=250ms",
                "memoryos.redis.reclaim-idle=500ms",
                "memoryos.redis.ingestion.stream=memoryos:test:cutover:ingestion",
                "memoryos.redis.ingestion.group=memoryos-test-cutover-ingestion",
                "memoryos.redis.ingestion.batch-size=4",
                "memoryos.redis.cleanup.stream=memoryos:test:cutover:cleanup",
                "memoryos.redis.cleanup.group=memoryos-test-cutover-cleanup",
                "memoryos.redis.cleanup.batch-size=4"
        }
)
@org.springframework.context.annotation.Import(WorkerFileProcessingIntegrationTest.TelemetryConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection", "unchecked", "resource", "HttpUrlsUsage"})
class WorkerFileProcessingIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired
    private io.memoryos.document.ExtractionArtifactPort extractionArtifacts;

    private static final java.util.List<io.opentelemetry.sdk.trace.data.SpanData> SPANS = new java.util.concurrent.CopyOnWriteArrayList<>();

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class TelemetryConfiguration {
        @org.springframework.context.annotation.Bean
        io.opentelemetry.api.OpenTelemetry operationTestTelemetry() {
            var processor = new io.opentelemetry.sdk.trace.SpanProcessor() {
                public void onStart(io.opentelemetry.context.Context parent, io.opentelemetry.sdk.trace.ReadWriteSpan span) {}
                public boolean isStartRequired() { return false; }
                public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) { SPANS.add(span.toSpanData()); }
                public boolean isEndRequired() { return true; }
            };
            return io.opentelemetry.sdk.OpenTelemetrySdk.builder().setTracerProvider(
                    io.opentelemetry.sdk.trace.SdkTracerProvider.builder().addSpanProcessor(processor).build()).build();
        }
    }

    private static final java.util.List<String> METRIC_EXPORTS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final com.sun.net.httpserver.HttpServer METRICS_RECEIVER = metricsReceiver();

    private static com.sun.net.httpserver.HttpServer metricsReceiver() {
        try {
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/metrics", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                METRIC_EXPORTS.add(new String(body, StandardCharsets.ISO_8859_1));
                String forward = System.getenv("MEMORYOS_TEST_OTLP_FORWARD");
                if (forward != null) {
                    var connection = (java.net.HttpURLConnection) URI.create(forward + "/v1/metrics").toURL().openConnection();
                    try {
                        connection.setConnectTimeout(2000);
                        connection.setReadTimeout(2000);
                        connection.setRequestMethod("POST");
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", "application/x-protobuf");
                        try (var output = connection.getOutputStream()) { output.write(body); }
                        if (connection.getResponseCode() != 200) throw new java.io.IOException("Collector rejected metrics");
                    } finally {
                        connection.disconnect();
                    }
                }
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not start metrics receiver", exception);
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void stopMetricsReceiver() { METRICS_RECEIVER.stop(0); }

    private static final String INGESTION_STREAM = "memoryos:test:cutover:ingestion";
    private static final String INGESTION_GROUP = "memoryos-test-cutover-ingestion";
    private static final String CLEANUP_STREAM = "memoryos:test:cutover:cleanup";
    private static final String CLEANUP_GROUP = "memoryos-test-cutover-cleanup";
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:17.11-alpine3.24@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73"
    ).asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("memoryos")
            .withUsername("memoryos")
            .withPassword("memoryos");

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse(
                    "minio/minio:RELEASE.2025-04-22T22-12-26Z"
                            + "@sha256:a1ea29fa28355559ef137d71fc570e508a214ec84ff8083e39bc5428980b015e"
            )
    )
            .withEnv("MINIO_ROOT_USER", "memoryos-test")
            .withEnv("MINIO_ROOT_PASSWORD", "memoryos-test-secret")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    private static final String OBJECT_BUCKET = "memoryos-test";

    private static final ActorId OWNER = new ActorId(
            UUID.fromString("ac009796-bf52-4d3b-b619-acbde4e46717")
    );
    private static final UUID TENANT_ID =
            UUID.fromString("4595ef61-4758-4dcf-982a-a0d69ceec87f");


    @Autowired
    private io.micrometer.core.instrument.MeterRegistry registry;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SourceManagementService sources;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private RedisStreamWorker worker;

    @Autowired
    private RedisExecutionTopology topology;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        if (System.getenv("DOCLING_TEST_ENDPOINT") != null) {
            registry.add("memoryos.extraction.docling.endpoint", () -> System.getenv("DOCLING_TEST_ENDPOINT"));
        }
        registry.add("management.otlp.metrics.export.url", () -> "http://127.0.0.1:" + METRICS_RECEIVER.getAddress().getPort() + "/v1/metrics");
        WorkerPostgresDatabase.configure(registry, POSTGRES);
        registry.add("memoryos.object-storage.s3.service-endpoint", WorkerFileProcessingIntegrationTest::minioEndpoint);
        registry.add("memoryos.object-storage.s3.upload-endpoint", WorkerFileProcessingIntegrationTest::minioEndpoint);
        registry.add("memoryos.object-storage.s3.region", () -> "us-east-1");
        registry.add("memoryos.object-storage.s3.bucket", () -> OBJECT_BUCKET);
        registry.add("memoryos.object-storage.s3.access-key", () -> "memoryos-test");
        registry.add("memoryos.object-storage.s3.secret-key", () -> "memoryos-test-secret");
        registry.add("memoryos.object-storage.s3.path-style-access", () -> "true");
        registry.add("memoryos.object-storage.s3.authorization-lifetime", () -> "10m");
    }

    @BeforeAll
    static void createObjectBucket() {
        try (S3Client client = s3Client()) {
            client.createBucket(CreateBucketRequest.builder().bucket(OBJECT_BUCKET).build());
        }
    }


    @BeforeEach
    void seedOwner() {
        UUID tenantId = TENANT_ID;
        jdbcClient.sql("INSERT INTO actors (id) VALUES (:id)").param("id", OWNER.value()).update();
        jdbcClient.sql("""
                        INSERT INTO tenants (
                            id, slug, display_name, status, bootstrap_reference
                        ) VALUES (
                            :id, 'worker-file', 'Worker file', 'ACTIVE', 'MEM-35-WORKER-TEST'
                        )
                        """)
                .param("id", tenantId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO tenant_memberships (
                            tenant_id, actor_id, role, status
                        ) VALUES (:tenantId, :actorId, 'OWNER', 'ACTIVE')
                        """)
                .param("tenantId", tenantId)
                .param("actorId", OWNER.value())
                .update();
        UUID adminGroupId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID basicGroupId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        jdbcClient.sql("""
                        INSERT INTO iam_groups (tenant_id, id, name, system_key)
                        VALUES (:tenantId, :adminGroupId, 'Admin', 'ADMIN'),
                               (:tenantId, :basicGroupId, 'Basic', 'BASIC')
                        """)
                .param("tenantId", tenantId)
                .param("adminGroupId", adminGroupId)
                .param("basicGroupId", basicGroupId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO iam_group_capability_grants (tenant_id, group_id, capability)
                        VALUES (:tenantId, :adminGroupId, 'IAM_ADMIN')
                        """)
                .param("tenantId", tenantId)
                .param("adminGroupId", adminGroupId)
                .update();
        jdbcClient.sql("""
                        INSERT INTO iam_group_memberships (tenant_id, group_id, actor_id)
                        VALUES (:tenantId, :adminGroupId, :actorId),
                               (:tenantId, :basicGroupId, :actorId)
                        """)
                .param("tenantId", tenantId)
                .param("adminGroupId", adminGroupId)
                .param("basicGroupId", basicGroupId)
                .param("actorId", OWNER.value())
                .update();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void redisStreamsIndexRemoveAndDeleteOneRealFile() throws Exception {
        worker.stop();
        var sourceId = sources.createFileSource(OWNER, "Worker knowledge", List.of()).source().id();
        boolean docling = System.getenv("DOCLING_TEST_ENDPOINT") != null;
        byte[] content = docling ? docxFixture() : "MemoryOS worker extraction".getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var authorization = sources.initiateUpload(
                OWNER,
                sourceId,
                new ObjectUploadSpecification(
                        docling ? "worker.docx" : "worker.txt",
                        docling ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "text/plain",
                        content.length,
                        new ContentSha256(sha256)
                )
        );
        HttpRequest.Builder uploadRequest = HttpRequest.newBuilder(authorization.authorization().uri());
        authorization.authorization().requiredHeaders().forEach(uploadRequest::header);
        HttpResponse<Void> uploadResponse;
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            uploadResponse = httpClient.send(
                    uploadRequest.PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
                    HttpResponse.BodyHandlers.discarding()
            );
        }
        assertEquals(200, uploadResponse.statusCode());
        io.memoryos.connector.SourceUploadReceipt upload;
        String originTrace = "1234567890abcdef1234567890abcdef";
        try (var _ = org.slf4j.MDC.putCloseable("traceId", originTrace);
             var _ = org.slf4j.MDC.putCloseable("spanId", "1234567890abcdef")) {
            upload = sources.finalizeUpload(OWNER, sourceId, authorization.uploadId());
        }
        assertEquals(originTrace, jdbcClient.sql("SELECT origin_trace_id FROM index_attempts WHERE id = :id")
                .param("id", upload.operation().id().value()).query(String.class).single());
        assertEquals(sha256, upload.item().sha256());
        String objectKey = jdbcClient.sql("""
                        SELECT object.object_key
                        FROM connector_item_versions version
                        JOIN stored_objects object ON object.id = version.stored_object_id
                        WHERE version.connector_item_id = :itemId
                        """)
                .param("itemId", upload.item().id().value())
                .query(String.class)
                .single();
        await(() -> redis.opsForStream().size(INGESTION_STREAM) == 1L);
        redis.delete(INGESTION_STREAM);
        topology.reconcileTopology();
        assertEquals(0L, redis.opsForStream().size(INGESTION_STREAM));
        await(() -> redis.opsForStream().size(INGESTION_STREAM) == 1L);
        var abandoned = redis.opsForStream().read(
                Consumer.from(INGESTION_GROUP, "abandoned-consumer"),
                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                StreamOffset.create(INGESTION_STREAM, ReadOffset.lastConsumed())
        );
        assertEquals(1, abandoned.size());
        assertEquals(originTrace, abandoned.getFirst().getValue().get("origin_trace_id"));
        assertEquals(
                1L,
                redis.opsForStream().pending(INGESTION_STREAM, INGESTION_GROUP).getTotalPendingMessages()
        );
        LockSupport.parkNanos(Duration.ofMillis(600).toNanos());
        worker.start();

        // The real isolated extractor permits 90 seconds, plus stream reclaim/startup.
        await(Duration.ofSeconds(120), () -> sources.getSource(OWNER, sourceId).source().status() == SourceStatus.ACTIVE);
        assertEquals(1L, sources.getSource(OWNER, sourceId).source().documentCount());
        await(() -> registry.get("memoryos.operation.outcomes").tags("workload", "INGESTION", "outcome", "COMPLETED").counter().count() == 1);
        var initialWait = registry.get("memoryos.operation.initial.queue.wait").tag("workload", "INGESTION").timer();
        assertEquals(1, initialWait.count());
        double databaseWaitSeconds = jdbcClient.sql("SELECT EXTRACT(EPOCH FROM started_at - created_at) FROM index_attempts WHERE id = :id")
                .param("id", upload.operation().id().value()).query(Double.class).single();
        org.assertj.core.api.Assertions.assertThat(initialWait.totalTime(java.util.concurrent.TimeUnit.SECONDS))
                .isCloseTo(databaseWaitSeconds, org.assertj.core.data.Offset.offset(0.000001));
        await(() -> SPANS.stream().anyMatch(span -> span.getName().equals("memoryos.operation.process")
                && span.getLinks().stream().anyMatch(link -> link.getSpanContext().getTraceId().equals(originTrace))));
        org.assertj.core.api.Assertions.assertThat(SPANS.stream().filter(span -> span.getName().equals("memoryos.operation.process")))
                .allMatch(span -> !span.getParentSpanContext().isValid());
        String artifactKey = jdbcClient.sql("""
                SELECT a.object_key FROM document_extraction_artifacts a
                JOIN documents v ON v.tenant_id=a.tenant_id AND v.extraction_artifact_id=a.id
                WHERE a.state='ACTIVE' AND a.write_complete=TRUE
                """).query(String.class).single();
        try (S3Client client = s3Client()) {
            String artifact = client.getObjectAsBytes(software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
                    .bucket(OBJECT_BUCKET).key(artifactKey).build()).asUtf8String();
            assertTrue(artifact.contains("MemoryOS worker extraction"));
        }
        // Rediscovery may publish again while the intentionally stopped consumer is waiting.
        // Require recovery after stream loss, not an exact timing-dependent delivery count.
        assertTrue(
                jdbcClient.sql("SELECT dispatch_attempts FROM index_attempts WHERE id = :id")
                        .param("id", upload.operation().id().value())
                        .query(Integer.class)
                        .single() >= 2
        );
        assertEquals(
                0L,
                redis.opsForStream().pending(
                        INGESTION_STREAM,
                        INGESTION_GROUP
                ).getTotalPendingMessages()
        );
        UUID deliveryId = jdbcClient.sql("SELECT delivery_id FROM index_attempts WHERE id = :id")
                .param("id", upload.operation().id().value())
                .query(UUID.class)
                .single();
        redis.opsForStream().add(INGESTION_STREAM, Map.of(
                "tenant_id", TENANT_ID.toString(),
                "operation_kind", "INGESTION",
                "operation_id", upload.operation().id().value().toString(),
                "delivery_id", deliveryId.toString()
        ));
        await(() -> redis.opsForStream().size(INGESTION_STREAM) == 0L);
        assertEquals(
                1,
                jdbcClient.sql("SELECT processing_attempts FROM index_attempts WHERE id = :id")
                        .param("id", upload.operation().id().value())
                        .query(Integer.class)
                        .single()
        );

        await(() -> registry.get("memoryos.operation.outcomes").tags("workload", "INGESTION", "outcome", "SKIPPED").counter().count() >= 1);
        assertEquals(1, initialWait.count());
        assertEquals(1, registry.get("memoryos.operation.outcomes").tags("workload", "INGESTION", "outcome", "COMPLETED").counter().count());
        sources.removeItem(OWNER, sourceId, upload.item().id());
        await(() -> sources.getSource(OWNER, sourceId).items().isEmpty());
        extractionArtifacts.cleanup();
        await(() -> jdbcClient.sql("SELECT COUNT(*) FROM document_extraction_artifacts")
                .query(Integer.class).single() == 0);
        assertEquals(0L, jdbcClient.sql("SELECT COUNT(*) FROM stored_objects").query(Long.class).single());
        assertEquals(0L, jdbcClient.sql("SELECT COUNT(*) FROM object_uploads").query(Long.class).single());
        assertEquals(0L, jdbcClient.sql("SELECT COUNT(*) FROM source_uploads").query(Long.class).single());
        try (S3Client client = s3Client()) {
            S3Exception missing = assertThrows(
                    S3Exception.class,
                    () -> client.headObject(HeadObjectRequest.builder().bucket(OBJECT_BUCKET).key(objectKey).build())
            );
            assertEquals(404, missing.statusCode());
        }

        sources.deleteSource(OWNER, sourceId);
        jdbcClient.sql("""
                        UPDATE tenants SET status = 'INACTIVE', updated_at = CURRENT_TIMESTAMP
                        WHERE id = :tenantId
                        """)
                .param("tenantId", TENANT_ID)
                .update();
        await(() -> jdbcClient.sql("""
                        SELECT COUNT(*) FROM connector_credential_pairs
                        WHERE id = :sourceId
                        """)
                .param("sourceId", sourceId.value())
                .query(Integer.class)
                .single() == 0);
        assertEquals(
                "SUCCEEDED",
                jdbcClient.sql("""
                                SELECT status FROM connector_cleanup_attempts
                                WHERE target_pair_id = :sourceId AND operation = 'DELETE_SOURCE'
                                """)
                        .param("sourceId", sourceId.value())
                        .query(String.class)
                        .single()
        );
        await(() -> registry.get("memoryos.operation.outcomes").tags("workload", "CLEANUP", "outcome", "COMPLETED").counter().count() == 2);
        int exportsBeforeFinalOutcomes = METRIC_EXPORTS.size();
        await(() -> METRIC_EXPORTS.size() > exportsBeforeFinalOutcomes);
        await(() -> METRIC_EXPORTS.stream().anyMatch(body -> body.contains("memoryos.operation.outcomes")
                && body.contains("memoryos.operation.initial.queue.wait") && body.contains("COMPLETED")
                && body.contains("SKIPPED") && body.contains("CLEANUP") && body.contains("memoryos-worker")));
        assertEquals(2, registry.get("memoryos.operation.initial.queue.wait").tag("workload", "CLEANUP").timer().count());
        assertEquals(
                0L,
                redis.opsForStream().pending(
                        CLEANUP_STREAM,
                        CLEANUP_GROUP
                ).getTotalPendingMessages()
        );
    }
    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
    private static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(minioEndpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("memoryos-test", "memoryos-test-secret")
                ))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }



    private static void await(BooleanSupplier condition) {
        await(Duration.ofSeconds(45), condition);
    }

    private static void await(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("worker condition did not converge within " + timeout);
            }
            LockSupport.parkNanos(Duration.ofMillis(25).toNanos());
        }
    }

    private static byte[] docxFixture() throws Exception {
        try (var output = new java.io.ByteArrayOutputStream(); var zip = new java.util.zip.ZipOutputStream(output)) {
            var entries = Map.of(
                    "[Content_Types].xml", """
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """,
                    "_rels/.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>
                    """,
                    "word/document.xml", """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
                    <w:p><w:r><w:t>MemoryOS worker extraction</w:t></w:r></w:p></w:body></w:document>
                    """);
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }
}
