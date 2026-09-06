package io.memoryos.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.IOException;
import java.net.InetAddress;
import static java.nio.charset.StandardCharsets.UTF_8;
import io.micrometer.tracing.Tracer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks",
                "memoryos.identity.audience=memoryos-api",
                "spring.security.oauth2.client.registration.memoryos.client-secret=client-secret",
                "arconia.multitenancy.resolution.fixed.tenant-identifier=10000000-0000-0000-0000-000000000024",
                "memoryos.initial-tenant.id=10000000-0000-0000-0000-000000000024",
                "memoryos.initial-tenant.owner-subject=smoke-owner",
                "memoryos.initial-tenant.slug=smoke",
                "memoryos.initial-tenant.display-name=Smoke",
                "memoryos.initial-tenant.change-reference=TEST-SMOKE-BOOTSTRAP",
                "spring.datasource.url=jdbc:h2:mem:api-smoke;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
@ActiveProfiles("staging")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:staging-telemetry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "management.otlp.metrics.export.step=1s",
        "MEMORYOS_RELEASE=telemetry-contract-test"
})
class StagingTelemetryIntegrationTest {
    private record Export(String path, byte[] body) {}
    private static final List<Export> EXPORTS = new CopyOnWriteArrayList<>();
    private static final HttpServer COLLECTOR = collector();

    private static final HttpServer IDENTITY_SERVER = startIdentityServer();
    private static final String BROWSER_ISSUER = "http://127.0.0.1:" + IDENTITY_SERVER.getAddress().getPort();

    @Autowired Tracer tracer;
    @Autowired io.micrometer.observation.ObservationRegistry observations;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    private static volatile String propagatedTraceparent;
    private static volatile boolean unavailable;
    private static final java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();


    @DynamicPropertySource
    static void telemetry(DynamicPropertyRegistry properties) {
        properties.add("MEMORYOS_OTLP_BASE_URL", () -> "http://127.0.0.1:" + COLLECTOR.getAddress().getPort());
        properties.add("MEMORYOS_RELEASE", () -> "telemetry-contract-test");
    }

    @Test
    void exportsCorrelatedStructuredLogAndTraceAndMetrics(org.springframework.boot.test.system.CapturedOutput output) {
        var span = tracer.nextSpan().name("memoryos.telemetry.contract").start();
        try (var scope = tracer.withSpan(span)) {
            LoggerFactory.getLogger(getClass()).atInfo().addKeyValue("event", "telemetry.contract")
                    .addKeyValue("operation_id", "contract-operation").log("Telemetry contract event");
            org.springframework.web.client.RestClient.builder().observationRegistry(observations).build().get().uri("http://127.0.0.1:" + COLLECTOR.getAddress().getPort() + "/echo").retrieve().toBodilessEntity();
            assertThat(propagatedTraceparent).contains(span.context().traceId());
        } finally {
            span.end();
        }
        assertThat(org.springframework.web.client.RestClient.create("http://127.0.0.1:" + port)
                .get().uri("/actuator/health").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
        int identityStatus = org.springframework.web.client.RestClient.create("http://127.0.0.1:" + port)
                .get().uri("/api/identity/me").exchange((request, response) -> response.getStatusCode().value());
        assertThat(identityStatus).isEqualTo(401);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(text("/v1/logs")).contains("Telemetry contract event", "telemetry.contract", "contract-operation");
            assertThat(text("/v1/traces")).contains("memoryos.telemetry.contract", "memoryos-api");
            assertThat(text("/v1/metrics")).contains("jvm.memory.used", "memoryos-api", "http.client.requests", "http.server.requests");
            String traceBytes = new String(java.util.HexFormat.of().parseHex(span.context().traceId()), StandardCharsets.ISO_8859_1);
            assertThat(text("/v1/logs")).contains(traceBytes);
            assertThat(text("/v1/traces")).contains(traceBytes);
        });
        assertThat(output.getOut()).contains("\"event\":\"telemetry.contract\"", "\"traceId\":\"" + span.context().traceId(), "\"release\":\"telemetry-contract-test\"");
        assertThat(text("/v1/logs").split("Telemetry contract event", -1)).hasSize(2);
        assertThat(text("/v1/logs")).doesNotContain("test-provisioner-secret", "test-secret-key");
        unavailable = true;
        try {
            LoggerFactory.getLogger(getClass()).info("Collector outage contract");
            await().atMost(Duration.ofSeconds(10)).until(() -> rejected.get() > 0);
            long start = System.nanoTime();
            assertThat(org.springframework.web.client.RestClient.create("http://127.0.0.1:" + port)
                    .get().uri("/actuator/health").retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        } finally { unavailable = false; }
        LoggerFactory.getLogger(getClass()).info("Collector recovered contract");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(text("/v1/logs")).contains("Collector recovered contract"));
    }

    private static String text(String path) {
        return EXPORTS.stream().filter(export -> export.path().equals(path))
                .map(export -> new String(export.body(), StandardCharsets.ISO_8859_1))
                .reduce("", String::concat);
    }

    private static HttpServer collector() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (unavailable && exchange.getRequestURI().getPath().startsWith("/v1/")) {
                    rejected.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close(); return;
                }
                EXPORTS.add(new Export(exchange.getRequestURI().getPath(), body));
                if (exchange.getRequestURI().getPath().equals("/echo")) {
                    propagatedTraceparent = exchange.getRequestHeaders().getFirst("traceparent");
                }
                String forward = System.getenv("MEMORYOS_TEST_OTLP_FORWARD");
                if (forward != null && exchange.getRequestURI().getPath().startsWith("/v1/")) {
                    var connection = (java.net.HttpURLConnection) java.net.URI.create(forward + exchange.getRequestURI().getPath()).toURL().openConnection();
                    connection.setConnectTimeout(2000); connection.setReadTimeout(2000);
                    connection.setRequestMethod("POST"); connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/x-protobuf");
                    try (var output = connection.getOutputStream()) { output.write(body); }
                    if (connection.getResponseCode() != 200) throw new IOException("Local Collector rejected test telemetry");
                    connection.disconnect();
                }
                exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @AfterAll static void stopCollector() { COLLECTOR.stop(0); IDENTITY_SERVER.stop(0); }
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
                try (var responseBody = exchange.getResponseBody()) {
                    responseBody.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start test identity server", exception);
        }
    }
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

}
