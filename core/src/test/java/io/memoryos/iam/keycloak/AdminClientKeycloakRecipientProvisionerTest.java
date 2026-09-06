package io.memoryos.iam.keycloak;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.iam.IdentityProvisioningException;
import io.memoryos.iam.IdentityProvisioningFailureReason;
import io.memoryos.iam.KeycloakRecipientProvisioning;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminClientKeycloakRecipientProvisionerTest {

    private static final String EMAIL = "member@example.com";

    private HttpServer server;
    private AtomicReference<String> usersResponse;
    private List<CapturedRequest> requests;
    private volatile long usersDelayMillis;
    private final AtomicInteger activationFailures = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        usersResponse = new AtomicReference<>("[]");
        requests = new ArrayList<>();
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
    void createsAndActivatesANewRecipient() {
        try (var provisioner = provisioner(Duration.ofSeconds(2))) {
            KeycloakRecipientProvisioning result = provisioner.provision(
                    EMAIL,
                    Instant.now().plus(Duration.ofHours(2))
            );

            assertEquals(KeycloakRecipientProvisioning.ACTIVATION_EMAIL_SENT, result);
            CapturedRequest create = request("POST", "/admin/realms/memoryos/users");
            assertTrue(create.body().contains("\"username\":\"member@example.com\""));
            assertTrue(create.body().contains("\"emailVerified\":false"));
            assertTrue(create.body().contains("\"VERIFY_EMAIL\""));
            assertTrue(create.body().contains("\"UPDATE_PASSWORD\""));
            assertTrue(create.body().contains("\"memoryos.provisioned\""));

            CapturedRequest activation = request(
                    "PUT",
                    "/admin/realms/memoryos/users/created-user/execute-actions-email"
            );
            assertTrue(activation.query().contains("client_id=memoryos-web"));
            assertTrue(activation.query().contains("redirect_uri="));
            assertTrue(activation.query().contains("lifespan="));
            assertTrue(activation.body().contains("VERIFY_EMAIL"));
            assertTrue(activation.body().contains("UPDATE_PASSWORD"));
            assertFalse((activation.query() + activation.body()).contains("invitation"));
        }
    }

    @Test
    void reusesAMemoryOsCreatedUnverifiedRecipient() {
        usersResponse.set(userJson(false, true));
        try (var provisioner = provisioner(Duration.ofSeconds(2))) {
            KeycloakRecipientProvisioning result = provisioner.provision(
                    EMAIL,
                    Instant.now().plus(Duration.ofMinutes(30))
            );

            assertEquals(KeycloakRecipientProvisioning.ACTIVATION_EMAIL_SENT, result);
            assertFalse(hasRequest("POST", "/admin/realms/memoryos/users"));
            assertTrue(hasRequest("PUT", "/admin/realms/memoryos/users/existing-user"));
            assertTrue(hasRequest(
                    "PUT",
                    "/admin/realms/memoryos/users/existing-user/execute-actions-email"
            ));
        }
    }

    @Test
    void reusesAnExistingVerifiedRecipientWithoutReset() {
        usersResponse.set(userJson(true, false));
        try (var provisioner = provisioner(Duration.ofSeconds(2))) {
            KeycloakRecipientProvisioning result = provisioner.provision(
                    EMAIL,
                    Instant.now().plus(Duration.ofHours(1))
            );

            assertEquals(KeycloakRecipientProvisioning.EXISTING_VERIFIED, result);
            assertFalse(hasRequest("POST", "/admin/realms/memoryos/users"));
            assertFalse(requests.stream().anyMatch(request ->
                    request.path().endsWith("/execute-actions-email")));
        }
    }

    @Test
    void rejectsUnownedOrAmbiguousUnverifiedRecipients() {
        usersResponse.set(userJson(false, false));
        try (var provisioner = provisioner(Duration.ofSeconds(2))) {
            IdentityProvisioningException unowned = assertThrows(
                    IdentityProvisioningException.class,
                    () -> provisioner.provision(EMAIL, Instant.now().plus(Duration.ofHours(1)))
            );
            assertEquals(IdentityProvisioningFailureReason.ACCOUNT_CONFLICT, unowned.reason());
        }

        usersResponse.set("[" + userObject("first", false, false)
                + "," + userObject("second", false, false) + "]");
        try (var provisioner = provisioner(Duration.ofSeconds(2))) {
            IdentityProvisioningException ambiguous = assertThrows(
                    IdentityProvisioningException.class,
                    () -> provisioner.provision(EMAIL, Instant.now().plus(Duration.ofHours(1)))
            );
            assertEquals(IdentityProvisioningFailureReason.ACCOUNT_CONFLICT, ambiguous.reason());
        }
    }

    @Test
    void retriesAfterProviderCreatedTheUserButActivationEmailFailed() {
        activationFailures.set(1);
        try (var provisioner = provisioner(Duration.ofSeconds(2))) {
            IdentityProvisioningException first = assertThrows(
                    IdentityProvisioningException.class,
                    () -> provisioner.provision(EMAIL, Instant.now().plus(Duration.ofHours(1)))
            );
            assertEquals(IdentityProvisioningFailureReason.PROVIDER_UNAVAILABLE, first.reason());

            KeycloakRecipientProvisioning retried = provisioner.provision(
                    EMAIL,
                    Instant.now().plus(Duration.ofHours(1))
            );
            assertEquals(KeycloakRecipientProvisioning.ACTIVATION_EMAIL_SENT, retried);
        }

        assertEquals(
                1L,
                requests.stream()
                        .filter(request -> request.method().equals("POST"))
                        .filter(request -> request.path().equals("/admin/realms/memoryos/users"))
                        .count()
        );
        assertEquals(
                2L,
                requests.stream()
                        .filter(request -> request.path().endsWith("/execute-actions-email"))
                        .count()
        );
    }

    @Test
    void boundsProviderCalls() {
        usersDelayMillis = 500;
        long started = System.nanoTime();
        try (var provisioner = provisioner(Duration.ofMillis(100))) {
            IdentityProvisioningException failure = assertThrows(
                    IdentityProvisioningException.class,
                    () -> provisioner.provision(EMAIL, Instant.now().plus(Duration.ofHours(1)))
            );
            assertEquals(IdentityProvisioningFailureReason.PROVIDER_UNAVAILABLE, failure.reason());
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
    }

    private AdminClientKeycloakRecipientProvisioner provisioner(Duration readTimeout) {
        return new AdminClientKeycloakRecipientProvisioner(new KeycloakAdminProperties(
                serverUrl(),
                "memoryos",
                "memoryos-user-provisioner",
                "provisioner-secret",
                "memoryos-web",
                "https://memoryos.example.test/invite/activate",
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                readTimeout
        ));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
        requests.add(new CapturedRequest(method, path, exchange.getRequestURI().getRawQuery(), body));

        if (path.equals("/realms/memoryos/protocol/openid-connect/token")) {
            json(exchange, 200, """
                    {"access_token":"admin-token","expires_in":300,"refresh_expires_in":0,"token_type":"Bearer","scope":""}
                    """);
            return;
        }
        if (path.equals("/admin/realms/memoryos/users") && method.equals("GET")) {
            sleep(usersDelayMillis);
            json(exchange, 200, usersResponse.get());
            return;
        }
        if (path.equals("/admin/realms/memoryos/users") && method.equals("POST")) {
            usersResponse.set(userJson(false, true));
            exchange.getResponseHeaders().add(
                    "Location",
                    serverUrl() + "/admin/realms/memoryos/users/created-user"
            );
            empty(exchange, 201);
            return;
        }
        if (path.equals("/admin/realms/memoryos/users/existing-user") && method.equals("GET")) {
            json(exchange, 200, usersResponse.get().substring(1, usersResponse.get().length() - 1));
            return;
        }
        if (path.equals("/admin/realms/memoryos/users/existing-user") && method.equals("PUT")) {
            empty(exchange, 204);
            return;
        }
        if (path.endsWith("/execute-actions-email") && method.equals("PUT")) {
            if (activationFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                json(exchange, 500, "{\"error\":\"mail failed\"}");
                return;
            }
            empty(exchange, 204);
            return;
        }
        json(exchange, 404, "{\"error\":\"not found\"}");
    }

    private CapturedRequest request(String method, String path) {
        return requests.stream()
                .filter(request -> request.method().equals(method) && request.path().equals(path))
                .findFirst()
                .orElseThrow();
    }

    private boolean hasRequest(String method, String path) {
        return requests.stream().anyMatch(request ->
                request.method().equals(method) && request.path().equals(path));
    }


    private String serverUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String userJson(boolean verified, boolean memoryOsProvisioned) {
        return "[" + userObject("existing-user", verified, memoryOsProvisioned) + "]";
    }

    private static String userObject(String id, boolean verified, boolean memoryOsProvisioned) {
        String attributes = memoryOsProvisioned
                ? "{\"memoryos.provisioned\":[\"true\"]}"
                : "{}";
        return "{\"id\":\"" + id
                + "\",\"username\":\"" + EMAIL
                + "\",\"email\":\"" + EMAIL
                + "\",\"emailVerified\":" + verified
                + ",\"enabled\":true,\"requiredActions\":[],\"attributes\":"
                + attributes + "}";
    }

    private static void sleep(long millis) {
        if (millis == 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test server interrupted", exception);
        }
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void empty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private record CapturedRequest(String method, String path, String query, String body) {
    }
}
