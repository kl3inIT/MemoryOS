package io.memoryos.chat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebHttpTest {
    @Test void rejectsPrivateLiteralDestinationsAndCredentials() {
        for (String url : new String[]{"http://127.0.0.1", "http://2130706433", "http://10.0.0.1", "http://169.254.169.254/latest/meta-data",
                "http://[::1]", "http://[fc00::1]", "http://[::ffff:127.0.0.1]", "file:///etc/passwd", "https://user:secret@example.com"})
            assertThrows(IllegalArgumentException.class, () -> WebHttp.pageUri(url), url);
    }
    @Test void publicAddressesKeepIpv4Ipv6ButNotPrivateTransitionNetworks() throws Exception {
        assertTrue(WebHttp.publicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(WebHttp.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));
        assertFalse(WebHttp.publicAddress(InetAddress.getByName("100.64.0.1")));
        assertFalse(WebHttp.publicAddress(InetAddress.getByName("2002:7f00:1::1")));
    }
    @Test void trustedProviderEndpointDoesNotRelaxPublicReaderOrFollowProviderRedirects() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            assertNull(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.getResponseHeaders().set("Location", "/secret");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try (var http = new WebHttp()) {
            String url = "http://localhost:" + server.getAddress().getPort();
            assertEquals(302, http.provider("GET", URI.create(url), Map.of(), null).status());
            assertEquals(1, requests.get());
            assertThrows(Exception.class, () -> http.page(url, () -> {}));
            assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }
    @Test void providerResponseIsBounded() throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(200, 3 * 1024 * 1024); exchange.close(); });
        server.start();
        try (var http = new WebHttp()) {
            assertThrows(IOException.class, () -> http.provider("GET", URI.create("http://localhost:" + server.getAddress().getPort()), Map.of(), null));
        } finally { server.stop(0); }
    }
}
