package io.memoryos.iam.keycloak;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;

class KeycloakProviderSessionTerminatorTest {

    private HttpServer server;
    private final AtomicInteger deleteStatus = new AtomicInteger(204);
    private final List<String> deletions = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void deletesTheKeycloakSessionNamedByTheSid() {
        try (var terminator = terminator(serverUrl())) {
            assertTrue(terminator.end("session-1"));
        }
        assertEquals(List.of("/admin/realms/memoryos/sessions/session-1"), deletions);
    }

    @Test
    void treatsASessionKeycloakNoLongerHoldsAsEnded() {
        deleteStatus.set(404);
        try (var terminator = terminator(serverUrl())) {
            assertTrue(terminator.end("expired-session"));
        }
    }

    @Test
    void reportsARefusedDeletion() {
        deleteStatus.set(403);
        try (var terminator = terminator(serverUrl())) {
            assertFalse(terminator.end("session-1"));
        }
    }

    @Test
    void reportsAnUnreachableKeycloak() {
        try (var terminator = terminator("http://127.0.0.1:1")) {
            assertFalse(terminator.end("session-1"));
        }
    }

    @Test
    void neverCallsKeycloakWithoutASessionId() {
        try (var terminator = terminator(serverUrl())) {
            assertFalse(terminator.end(null));
            assertFalse(terminator.end(" "));
        }
        assertTrue(deletions.isEmpty());
    }

    private CloseableTerminator terminator(String serverUrl) {
        var properties = new KeycloakAdminProperties(
                serverUrl,
                "memoryos",
                "memoryos-user-provisioner",
                "provisioner-secret",
                "memoryos-web",
                "https://memoryos.example.test/invite/activate",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)
        );
        var keycloak = new KeycloakAdminConfiguration().keycloakAdminClient(properties);
        return new CloseableTerminator(keycloak, new KeycloakProviderSessionTerminator(keycloak, properties));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        exchange.getRequestBody().readAllBytes();
        if (path.equals("/realms/memoryos/protocol/openid-connect/token")) {
            byte[] body = """
                    {"access_token":"admin-token","expires_in":300,"refresh_expires_in":0,"token_type":"Bearer","scope":""}
                    """.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        if (path.startsWith("/admin/realms/memoryos/sessions/") && exchange.getRequestMethod().equals("DELETE")) {
            deletions.add(path);
            exchange.sendResponseHeaders(deleteStatus.get(), -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }

    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private record CloseableTerminator(Keycloak keycloak, KeycloakProviderSessionTerminator terminator)
            implements AutoCloseable {

        boolean end(String providerSessionId) {
            return terminator.end(providerSessionId);
        }

        @Override
        public void close() {
            keycloak.close();
        }
    }
}
