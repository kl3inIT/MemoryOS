package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class BoundedDoclingClientTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;

    @Test
    void sourceAndMultipartUseIdenticalSdkOptionsAndPrivateAuthentication() throws Exception {
        var requests = new java.util.concurrent.LinkedBlockingQueue<Captured>();
        var mapper = new tools.jackson.databind.ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert/", exchange -> {
            try (exchange) {
                requests.add(new Captured(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("X-Api-Key"),
                        new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                byte[] response = "{\"status\":\"success\",\"document\":{\"filename\":\"document.pdf\",\"text_content\":\"hello\"},\"errors\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        var properties = new DoclingProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null,
                Duration.ofSeconds(5), 200, "test-private-key", ai.docling.serve.api.convert.request.options.OcrEngine.TESSERACT,
                java.util.List.of("vie", "eng"));
        var file = temporary.resolve("file.pdf"); java.nio.file.Files.writeString(file, "test file content");
        try (var client = BoundedDoclingClient.create(properties)) {
            client.convertSource(ai.docling.serve.api.convert.request.ConvertDocumentRequest.builder()
                    .source(ai.docling.serve.api.convert.request.source.FileSource.builder().filename("document.pdf")
                            .base64String(java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(file))).build())
                    .options(properties.options()).target(ai.docling.serve.api.convert.request.target.InBodyTarget.builder().build()).build());
            client.convertFile(file, ".pdf", properties);
            var source = java.util.Objects.requireNonNull(requests.poll(5, java.util.concurrent.TimeUnit.SECONDS));
            var multipart = java.util.Objects.requireNonNull(requests.poll(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("/v1/convert/source", source.path()); assertEquals("/v1/convert/file", multipart.path());
            assertEquals("test-private-key", source.key()); assertEquals(source.key(), multipart.key());
            var options = mapper.readTree(source.body()).path("options");
            assertEquals("tesseract", options.path("ocr_engine").asString());
            assertEquals(5, options.path("document_timeout").asInt());
            options.properties().forEach(option -> {
                var values = option.getValue().isArray() ? option.getValue() : java.util.List.of(option.getValue());
                for (var value : values) assertTrue(multipart.body().contains("name=\"" + option.getKey() + "\"\r\n\r\n" + value.asString() + "\r\n"), option.getKey());
            });
            assertTrue(multipart.body().contains("test file content"));
            assertFalse(source.body().contains(properties.apiKey())); assertFalse(multipart.body().contains(properties.apiKey()));
            assertFalse(properties.toString().contains(properties.apiKey()));
            assertTrue(properties.parserConfiguration(262_144_000).contains("maxInput=262144000"));
            assertTrue(properties.parserConfiguration(10_485_760).contains("ocr=tesseract:vie,eng"));
        } finally { server.stop(0); }
    }

    private record Captured(String path, String key, String body) {}

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
