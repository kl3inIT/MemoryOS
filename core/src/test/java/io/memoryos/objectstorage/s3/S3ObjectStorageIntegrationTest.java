package io.memoryos.objectstorage.s3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.memoryos.objectstorage.ContentSha256;
import io.memoryos.objectstorage.ObjectKey;
import io.memoryos.objectstorage.ObjectStorageException;
import io.memoryos.objectstorage.ObjectStorageFailureCode;
import io.memoryos.objectstorage.UploadConstraints;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Testcontainers
@SuppressWarnings({"resource", "HttpUrlsUsage"})
class S3ObjectStorageIntegrationTest {
    private static final String ACCESS_KEY = "memoryos-test";
    private static final String SECRET_KEY = "memoryos-test-secret";
    private static final String BUCKET = "object-storage-contract";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z")
    )
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_API_CORS_ALLOW_ORIGIN", "http://localhost:4173")
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @BeforeAll
    static void createBucket() {
        try (var client = client()) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            client.putObject(
                    PutObjectRequest.builder()
                            .bucket(BUCKET)
                            .key("system/readiness")
                            .contentType("text/plain")
                            .build(),
                    RequestBody.fromString("memoryos-ready")
            );
        }
    }
    @AfterAll
    static void closeHttpClient() {
        HTTP.close();
    }


    @Test
    void probesAReadinessSentinelWithoutRequiringUploadChecksumMetadata() {
        try (var storage = storage(Duration.ofMinutes(5))) {
            storage.verifyReadable(new ObjectKey("system/readiness"));
        }
    }

    @Test
    void allowsOnlyTheConfiguredBrowserOriginToSendSignedUploadHeaders() throws Exception {
        URI endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        var request = HttpRequest.newBuilder(endpoint.resolve("/" + BUCKET + "/raw/cors-check"))
                .header("Origin", "http://localhost:4173")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type,x-amz-checksum-sha256")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        var response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());

        assertTrue(response.statusCode() == 200 || response.statusCode() == 204);
        assertEquals(
                "http://localhost:4173",
                response.headers().firstValue("access-control-allow-origin").orElseThrow()
        );
        assertTrue(response.headers().firstValue("access-control-allow-methods").orElseThrow().contains("PUT"));
        var rejectedRequest = HttpRequest.newBuilder(endpoint.resolve("/" + BUCKET + "/raw/cors-check"))
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type,x-amz-checksum-sha256")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        var rejected = HTTP.send(rejectedRequest,
        HttpResponse.BodyHandlers.discarding());
        assertTrue(rejected.headers().firstValue("access-control-allow-origin").isEmpty());
    }

    @Test
    void rejectsContentThatDoesNotMatchTheSignedChecksum() throws Exception {
        byte[] declared = "declared".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] altered = "alteredd".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var checksum = new ContentSha256(
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(declared))
        );
        var key = new ObjectKey(
                "raw/10000000-0000-0000-0000-000000000052/40000000-0000-0000-0000-000000000052"
        );

        try (var storage = storage(Duration.ofMinutes(5))) {
            var authorization = storage.authorizeUpload(
                    key,
                    new UploadConstraints(altered.length, "text/plain", checksum)
            );
            var request = HttpRequest.newBuilder(authorization.uri());
            authorization.requiredHeaders().forEach(request::header);
            var response = HTTP.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(altered)).build(),
            HttpResponse.BodyHandlers.discarding());

            assertEquals(400, response.statusCode());
            assertEquals(
                    ObjectStorageFailureCode.NOT_FOUND,
                    assertThrows(ObjectStorageException.class, () -> storage.inspect(key)).code()
            );
        }
    }

    @Test
    void presignsVerifiesStreamsAndIdempotentlyDeletesObjects() throws Exception {
        byte[] content = "provider-neutral object storage".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var checksum = new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        var key = new ObjectKey("raw/10000000-0000-0000-0000-000000000052/20000000-0000-0000-0000-000000000052");

        try (var storage = storage(Duration.ofMinutes(5))) {
            var authorization = storage.authorizeUpload(
                    key,
                    new UploadConstraints(content.length, "text/plain", checksum)
            );

            assertEquals("PUT", authorization.method());
            assertFalse(authorization.requiredHeaders().keySet().stream().anyMatch("host"::equalsIgnoreCase));
            assertTrue(authorization.requiredHeaders().keySet().stream()
                    .anyMatch("x-amz-checksum-sha256"::equalsIgnoreCase));

            var request = HttpRequest.newBuilder(authorization.uri());
            authorization.requiredHeaders().forEach(request::header);
            var response = HTTP.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
            HttpResponse.BodyHandlers.discarding());
            assertEquals(200, response.statusCode());

            var metadata = storage.inspect(key);
            assertEquals(content.length, metadata.sizeBytes());
            assertEquals("text/plain", metadata.mediaType());
            assertEquals(checksum, metadata.checksum());
            try (var object = storage.open(key)) {
                assertEquals(metadata, object.metadata());
                assertArrayEquals(content, object.inputStream().readAllBytes());
            }

            storage.delete(key);
            storage.delete(key);
            var missing = assertThrows(ObjectStorageException.class, () -> storage.inspect(key));
            assertEquals(ObjectStorageFailureCode.NOT_FOUND, missing.code());
            assertFalse(missing.retryable());
        }
    }

    @Test
    void expiredAuthorizationCannotCreateAnObject() throws Exception {
        byte[] content = "expired".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var checksum = new ContentSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        var key = new ObjectKey("raw/10000000-0000-0000-0000-000000000052/30000000-0000-0000-0000-000000000052");

        try (var storage = storage(Duration.ofSeconds(1))) {
            var authorization = storage.authorizeUpload(
                    key,
                    new UploadConstraints(content.length, "text/plain", checksum)
            );
            Thread.sleep(Duration.ofSeconds(2));

            var request = HttpRequest.newBuilder(authorization.uri());
            authorization.requiredHeaders().forEach(request::header);
            var response = HTTP.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
            HttpResponse.BodyHandlers.discarding());
            assertEquals(403, response.statusCode());
        }
    }

    private static S3ObjectStorage storage(Duration authorizationLifetime) {
        URI endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        var properties = new S3ObjectStorageProperties(
                endpoint,
                endpoint,
                "us-east-1",
                BUCKET,
                ACCESS_KEY,
                SECRET_KEY,
                true,
                "system/readiness",
                authorizationLifetime,
                Duration.ofSeconds(2),
                Duration.ofSeconds(10)
        );
        return S3ObjectStorage.create(properties, Clock.systemUTC());
    }

    private static S3Client client() {
        URI endpoint = URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        return S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
