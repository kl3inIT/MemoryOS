package io.memoryos.chat.image;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImageHttpTest {
    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private final ImageHttp http = new ImageHttp();
    private HttpServer server;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.start();
    }

    @AfterEach void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private URI uri(String path) { return URI.create("http://localhost:" + server.getAddress().getPort() + path); }

    @Test void aJsonPostCarriesItsHeadersAndBodyAndReturnsTheBytes() throws Exception {
        var contentType = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var sent = new AtomicReference<String>();
        server.createContext("/images/generations", exchange -> {
            try (exchange) {
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                sent.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
                exchange.sendResponseHeaders(200, 3);
                exchange.getResponseBody().write(new byte[]{1, 2, 3});
            }
        });
        var response = http.post(uri("/images/generations"), Map.of("Authorization", "Bearer test-secret"),
                "{\"prompt\":\"một chiếc xe đạp đỏ\"}");
        assertEquals(200, response.status());
        assertArrayEquals(new byte[]{1, 2, 3}, response.bytes());
        assertEquals("application/json", contentType.get());
        assertEquals("Bearer test-secret", authorization.get());
        assertEquals("{\"prompt\":\"một chiếc xe đạp đỏ\"}", sent.get());
    }

    @Test void aMultipartPostSendsTheTextFieldsInOrderAndThenTheFile() throws Exception {
        var contentType = new AtomicReference<String>();
        var transferEncoding = new AtomicReference<String>();
        var sent = new AtomicReference<byte[]>();
        server.createContext("/images/edits", exchange -> {
            try (exchange) {
                contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                transferEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
                sent.set(exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[]{'{', '}'});
            }
        });
        var fields = new LinkedHashMap<String, String>();
        fields.put("prompt", "làm áo màu đỏ");
        fields.put("width", "384");
        byte[] image = {(byte) 0x89, 'P', 'N', 'G', 0, (byte) 0xFF};
        var response = http.postMultipart(uri("/images/edits"), Map.of("Authorization", "Bearer test-secret"), fields,
                List.of(new ImageHttp.FilePart("input_image_0", "image.png", "image/png", image)));
        assertEquals(200, response.status());
        assertTrue(contentType.get().startsWith("multipart/form-data;"), contentType.get());
        assertTrue(contentType.get().contains("boundary="), contentType.get());
        // Spring writes multipart as it goes, so the request declares no length.
        assertEquals("chunked", transferEncoding.get());
        // Bytes as single characters, so the binary part can be searched as text.
        String body = new String(sent.get(), ISO_8859_1);
        int prompt = body.indexOf("Content-Disposition: form-data; name=\"prompt\"");
        int width = body.indexOf("Content-Disposition: form-data; name=\"width\"");
        int file = body.indexOf("Content-Disposition: form-data; name=\"input_image_0\"; filename=\"image.png\"");
        assertTrue(prompt >= 0 && prompt < width && width < file, body);
        assertTrue(body.contains("Content-Type: text/plain;charset=UTF-8"), body);
        assertTrue(body.contains("\r\n\r\n" + new String("làm áo màu đỏ".getBytes(UTF_8), ISO_8859_1) + "\r\n"), body);
        assertTrue(body.contains("Content-Type: image/png"), body);
        assertTrue(body.contains("\r\n\r\n" + new String(image, ISO_8859_1) + "\r\n"), body);
    }

    @Test void aFailedAnswerIsItsStatusAndItsBodyIsNeverRead() throws Exception {
        server.createContext("/failed", exchange -> {
            try (exchange; OutputStream body = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(401, 0);
                byte[] diagnostic = "secret-provider-diagnostic".getBytes(UTF_8);
                while (true) {
                    body.write(diagnostic);
                    body.flush();
                }
            } catch (IOException gone) {
                // The client left without reading.
            }
        });
        var response = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> http.post(uri("/failed"), Map.of(), "{}"));
        assertEquals(401, response.status());
        assertEquals(0, response.bytes().length);
    }

    @Test void aRedirectIsReturnedAndNotFollowed() throws Exception {
        var followed = new AtomicInteger();
        server.createContext("/moved", exchange -> {
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
        assertEquals(302, http.post(uri("/moved"), Map.of("Authorization", "Bearer test-secret"), "{}").status());
        assertEquals(0, followed.get());
    }

    @Test void aResponseOverTwentyMebibytesIsRefused() {
        server.createContext("/huge", exchange -> {
            try (exchange; OutputStream body = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(200, 20 * 1024 * 1024 + 1);
                body.write(new byte[8192]);
                body.flush();
            } catch (IOException gone) {
                // The client refused the declared length and left.
            }
        });
        var failure = assertThrows(IOException.class, () -> http.post(uri("/huge"), Map.of(), "{}"));
        assertEquals("Image response too large", failure.getMessage());
    }

    @Test void anUnreachableProviderIsAnIoFailureThatDoesNotNameTheUrl() {
        // Nothing listens on port 1.
        URI unreachable = URI.create("http://localhost:1/gone?key=test-secret");
        var failure = assertThrows(IOException.class, () -> http.post(unreachable, Map.of(), "{}"));
        assertTrue(failure.getMessage() == null || !failure.getMessage().contains("test-secret"), failure.getMessage());
    }
}
