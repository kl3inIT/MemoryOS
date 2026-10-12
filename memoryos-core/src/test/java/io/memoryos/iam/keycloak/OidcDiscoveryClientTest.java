package io.memoryos.iam.keycloak;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.iam.IdentityProviderException;
import io.memoryos.iam.IdentityProviderFailureReason;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OidcDiscoveryClientTest {
    private static final String DISCOVERY = "/realms/upstream/.well-known/openid-configuration";
    private final OidcDiscoveryClient client = new OidcDiscoveryClient();
    private HttpServer server;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private String issuer() { return "http://localhost:" + server.getAddress().getPort() + "/realms/upstream"; }

    private void answer(int status, String body) {
        server.createContext(DISCOVERY, exchange -> {
            try (exchange) {
                byte[] bytes = body.getBytes(UTF_8);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            }
        });
    }

    private String document(String issuer, String extra) {
        return "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"https://idp.example/auth\","
                + "\"token_endpoint\":\"https://idp.example/token\",\"jwks_uri\":\"https://idp.example/jwks\"" + extra + "}";
    }

    private void assertFails(IdentityProviderFailureReason reason) {
        var failure = assertThrows(IdentityProviderException.class, () -> client.discover(issuer()));
        assertEquals(reason.code(), failure.code());
    }

    @Test void readsTheEndpointsOfADocumentWhoseIssuerMatches() {
        answer(200, document(issuer(), ",\"end_session_endpoint\":\"https://idp.example/logout\",\"userinfo_endpoint\":7"));
        var discovered = client.discover(issuer());
        assertEquals(issuer(), discovered.issuer());
        assertEquals("https://idp.example/auth", discovered.authorizationUrl());
        assertEquals("https://idp.example/token", discovered.tokenUrl());
        assertEquals("https://idp.example/jwks", discovered.jwksUrl());
        assertEquals("https://idp.example/logout", discovered.logoutUrl());
        // A field that is not text is treated as absent.
        assertNull(discovered.userInfoUrl());
    }

    @Test void refusesADocumentThatNamesAnotherIssuer() {
        answer(200, document("https://elsewhere.example/realms/upstream", ""));
        assertFails(IdentityProviderFailureReason.DISCOVERY_FAILED);
    }

    @Test void refusesADocumentWithoutARequiredEndpointOrThatIsNotJson() {
        answer(200, "{\"issuer\":\"" + issuer() + "\",\"authorization_endpoint\":\"https://idp.example/auth\"}");
        assertFails(IdentityProviderFailureReason.DISCOVERY_FAILED);
        server.removeContext(DISCOVERY);
        answer(200, "<html>not a discovery document</html>");
        assertFails(IdentityProviderFailureReason.DISCOVERY_FAILED);
    }

    @Test void refusesADocumentLargerThanItsBound() {
        answer(200, document(issuer(), ",\"padding\":\"" + "x".repeat(65536) + "\""));
        assertFails(IdentityProviderFailureReason.DISCOVERY_FAILED);
    }

    @Test void refusesAnyStatusButOkAndNeverFollowsARedirect() {
        var followed = new AtomicInteger();
        server.createContext(DISCOVERY, exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/elsewhere");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/elsewhere", exchange -> {
            try (exchange) {
                followed.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            }
        });
        assertFails(IdentityProviderFailureReason.DISCOVERY_FAILED);
        assertEquals(0, followed.get());
        server.removeContext(DISCOVERY);
        answer(404, "");
        assertFails(IdentityProviderFailureReason.DISCOVERY_FAILED);
    }

    @Test void reportsAnIssuerThatCannotBeReachedAsUnavailable() {
        // Nothing listens on port 1.
        var failure = assertThrows(IdentityProviderException.class,
                () -> client.discover("http://localhost:1/realms/upstream"));
        assertEquals(IdentityProviderFailureReason.PROVIDER_UNAVAILABLE.code(), failure.code());
    }
}
