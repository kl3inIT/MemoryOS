package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BoundedDoclingClientTest {
    @Test
    void refusesRedirectsWithoutFollowingTheLocation() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/private");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        try (var client = client(server)) {
            assertEquals("Docling redirects are not allowed",
                    assertThrows(IllegalStateException.class, client::health).getMessage());
        } finally { server.stop(0); }
    }

    @Test
    void rejectsOversizedBodyBeforeJsonDeserialization() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 67_108_865);
                byte[] block = new byte[65536];
                for (int i = 0; i < 1024; i++) exchange.getResponseBody().write(block);
                exchange.getResponseBody().write(0);
            } catch (java.io.IOException ignored) {
                // Client cancellation is the expected result of the bounded body handler.
            }
        });
        server.start();
        try (var client = client(server)) {
            var error = assertThrows(ai.docling.serve.client.DoclingServeClientException.class, client::health);
            assertInstanceOf(java.io.IOException.class, error.getCause());
        } finally { server.stop(0); }
    }

    private BoundedDoclingClient client(HttpServer server) {
        return BoundedDoclingClient.create(new DoclingProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, Duration.ofSeconds(5), 200));
    }
}
