package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;

import ai.docling.serve.client.DoclingServeClientException;
import io.memoryos.connector.SourceInputDescriptor;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

class BoundedDoclingClientTest {
    private static final String TEST_API_KEY = "loopback-test-api-key";
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
                byte[] response = (exchange.getRequestURI().getPath().endsWith("/async")
                        ? "{\"task_id\":\"options-task\",\"task_status\":\"success\"}"
                        : "{\"status\":\"success\",\"document\":{\"filename\":\"document.pdf\",\"text_content\":\"hello\"},\"errors\":[]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.createContext("/v1/result/options-task", exchange ->
                respond(exchange, 200, "{\"status\":\"success\",\"document\":{\"filename\":\"document.pdf\",\"text_content\":\"hello\"},\"errors\":[]}"));
        server.start();
        var properties = new DoclingProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null,
                Duration.ofSeconds(5), 200, ai.docling.serve.api.convert.request.options.OcrEngine.TESSERACT,
                java.util.List.of("vie", "eng"), true, "test-private-key");
        var file = temporary.resolve("file.pdf"); java.nio.file.Files.writeString(file, "test file content");
        try (var client = BoundedDoclingClient.create(properties)) {
            client.convertDocument(ai.docling.serve.api.convert.request.ConvertDocumentRequest.builder()
                    .source(ai.docling.serve.api.convert.request.source.FileSource.builder().filename("document.pdf")
                            .base64String(java.util.Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(file))).build())
                    .options(properties.options()).target(ai.docling.serve.api.convert.request.target.InBodyTarget.builder().build()).build());
            client.convertFile(file, ".pdf", properties);
            var source = java.util.Objects.requireNonNull(requests.poll(5, java.util.concurrent.TimeUnit.SECONDS));
            var multipart = java.util.Objects.requireNonNull(requests.poll(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("/v1/convert/source/async", source.path()); assertEquals("/v1/convert/file", multipart.path());
            assertEquals("test-private-key", source.key()); assertEquals(source.key(), multipart.key());
            var options = mapper.readTree(source.body()).path("options");
            assertEquals("tesseract", options.path("ocr_engine").asString());
            assertEquals(5, options.path("document_timeout").asInt());
            assertTrue(options.path("force_ocr").asBoolean());
            assertFalse(options.path("abort_on_error").asBoolean());
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
    void authenticatedServerAcceptsTheKeyAndRejectsMissingCredentials() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            if (TEST_API_KEY.equals(exchange.getRequestHeaders().getFirst("X-Api-Key"))) {
                respond(exchange, 200, "{\"status\":\"ok\"}");
            } else {
                respond(exchange, 403, "{\"detail\":\"Forbidden\"}");
            }
        });
        server.start();
        try (var authenticated = client(server, TEST_API_KEY); var unauthenticated = client(server, null)) {
            assertEquals("ok", authenticated.health().getStatus());
            assertEquals(403, assertThrows(DoclingServeClientException.class, unauthenticated::health).getStatusCode());
        } finally { server.stop(0); }
    }

    @Test
    void nullAndEmptyKeysSendNoAuthenticationHeader() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            if (exchange.getRequestHeaders().containsKey("X-Api-Key")) {
                respond(exchange, 400, "{\"detail\":\"Unexpected credentials\"}");
            } else {
                respond(exchange, 200, "{\"status\":\"ok\"}");
            }
        });
        server.start();
        try {
            for (String key : new String[]{null, ""}) {
                try (var client = client(server, key)) {
                    assertEquals("ok", client.health().getStatus());
                }
            }
        } finally { server.stop(0); }
    }

    @Test
    void refusesRedirectsWithoutSendingCredentialsToTheTarget() throws Exception {
        var targetRequests = new AtomicInteger();
        var authenticatedRequests = new AtomicInteger();
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/private", exchange -> {
            targetRequests.incrementAndGet();
            respond(exchange, 200, "{\"status\":\"ok\"}");
        });
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            if (TEST_API_KEY.equals(exchange.getRequestHeaders().getFirst("X-Api-Key"))) {
                authenticatedRequests.incrementAndGet();
            }
            try (exchange) {
                exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/private");
                exchange.sendResponseHeaders(307, -1);
            }
        });
        target.start();
        server.start();
        try (var client = client(server, TEST_API_KEY)) {
            assertEquals(307, assertThrows(DoclingServeClientException.class, client::health).getStatusCode());
            assertEquals(1, authenticatedRequests.get());
            assertEquals(0, targetRequests.get());
        } finally {
            server.stop(0);
            target.stop(0);
        }
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
        try (var client = client(server, null)) {
            var error = assertThrows(DoclingServeClientException.class, client::health);
            assertInstanceOf(java.io.IOException.class, error.getCause());
        } finally { server.stop(0); }
    }

    @Test
    void observesOneSubmittedTaskAndPublishesOnlyItsCompletedDocument() throws Exception {
        var submissions = new AtomicInteger();
        var polls = new AtomicInteger();
        var synchronous = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert/source/async", exchange -> {
            exchange.getRequestBody().readAllBytes();
            submissions.incrementAndGet();
            respond(exchange, 200, "{\"task_id\":\"task-one\",\"task_status\":\"pending\"}");
        });
        server.createContext("/v1/convert/source", exchange -> {
            synchronous.incrementAndGet();
            respond(exchange, 504, "{\"detail\":\"synchronous deadline\"}");
        });
        server.createContext("/v1/status/poll/task-one", exchange -> {
            if (polls.incrementAndGet() == 1) respond(exchange, 503, "{\"detail\":\"temporary outage\"}");
            else respond(exchange, 200, "{\"task_id\":\"task-one\",\"task_status\":\"success\"}");
        });
        server.createContext("/v1/result/task-one", exchange -> respond(exchange, 200, """
                {"status":"success","errors":[],"document":{"filename":"document.pdf","json_content":{
                  "schema_name":"DoclingDocument","version":"1.10.0","name":"test",
                  "body":{"self_ref":"#/body","children":[{"$ref":"#/texts/0"}]},
                  "texts":[{"self_ref":"#/texts/0","label":"text","text":"Completed document",
                  "orig":"Completed document","prov":[]}],"pages":{}}}}
                """));
        server.start();
        try (var extractor = extractor(server)) {
            assertEquals("Completed document", extractPdf(extractor).normalizedText());
            assertEquals(1, submissions.get());
            assertEquals(2, polls.get());
            assertEquals(0, synchronous.get());
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @CsvSource({"policy,failure,WRITE_LIMIT", "timeout,partial_success,TIMEOUT", "unknown,success,MALFORMED"})
    void rejectsStructuredFailuresEvenWhenTheTaskClaimsSuccess(
            String category, String status, ExtractionFailure expected) throws Exception {
        var submissions = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert/source/async", exchange -> {
            var request = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
            submissions.incrementAndGet();
            String taskStatus = request.at("/options/abort_on_error").asBoolean() ? "failure" : "success";
            respond(exchange, 200, "{\"task_id\":\"rejected\",\"task_status\":\"" + taskStatus + "\"}");
        });
        server.createContext("/v1/result/rejected", exchange -> respond(exchange, 200,
                "{\"document\":{\"filename\":\"document.pdf\",\"json_content\":null},\"status\":\"" + status
                        + "\",\"errors\":[{\"component_type\":\"user_input\",\"category\":\"" + category
                        + "\",\"error_message\":\"private-document-content\"}],\"processing_time\":0.001}"));
        server.start();
        try (var extractor = extractor(server)) {
            var error = assertThrows(ExtractionException.class, () -> extractPdf(extractor));
            assertEquals(expected, error.failure());
            assertFalse(error.toString().contains("private-document-content"));
            assertNull(error.getCause());
            assertEquals(1, submissions.get());
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @CsvSource({"413,WRITE_LIMIT", "504,TIMEOUT", "503,INTERNAL"})
    void terminatesHttpFailuresWithoutLeakingBodiesOrResubmitting(int status, ExtractionFailure expected) throws Exception {
        var submissions = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert/source/async", exchange -> {
            exchange.getRequestBody().readAllBytes();
            submissions.incrementAndGet();
            respond(exchange, status, "{\"detail\":\"private-document-content\"}");
        });
        server.start();
        try (var extractor = extractor(server)) {
            var error = assertThrows(ExtractionException.class, () -> extractPdf(extractor));
            assertEquals(expected, error.failure());
            assertFalse(error.toString().contains("private-document-content"));
            assertNull(error.getCause());
            assertEquals(1, submissions.get());
        } finally { server.stop(0); }
    }

    @Test
    void interruptionStopsObservationWithoutSubmittingAgain() throws Exception {
        var submitted = new java.util.concurrent.CountDownLatch(1);
        var submissions = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/convert/source/async", exchange -> {
            exchange.getRequestBody().readAllBytes();
            submissions.incrementAndGet();
            respond(exchange, 200, "{\"task_id\":\"pending\",\"task_status\":\"pending\"}");
            submitted.countDown();
        });
        server.start();
        try (var extractor = extractor(server)) {
            var outcome = new java.util.concurrent.CompletableFuture<ExtractionException>();
            var observer = Thread.ofVirtual().start(() -> {
                try {
                    extractPdf(extractor);
                    outcome.completeExceptionally(new AssertionError("Pending task was published"));
                } catch (ExtractionException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        outcome.completeExceptionally(new AssertionError("Interrupt status was lost"));
                    } else {
                        outcome.complete(e);
                    }
                } catch (Exception e) { outcome.completeExceptionally(e); }
            });
            try {
                assertTrue(submitted.await(10, java.util.concurrent.TimeUnit.SECONDS));
                observer.interrupt();
                assertEquals(ExtractionFailure.TIMEOUT, outcome.get(10, java.util.concurrent.TimeUnit.SECONDS).failure());
                assertEquals(1, submissions.get());
            } finally {
                observer.interrupt();
                observer.join(10000);
            }
        } finally { server.stop(0); }
    }

    private DoclingSourceContentExtractor extractor(HttpServer server) {
        return new DoclingSourceContentExtractor(new DoclingProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null,
                Duration.ofSeconds(5), 200, null, null, false, TEST_API_KEY), new ObjectMapper());
    }

    private io.memoryos.document.DocumentContent extractPdf(DoclingSourceContentExtractor extractor) throws Exception {
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            pdf.save(out);
            return extractor.extract(out.toByteArray(), "file.pdf", "application/pdf", SourceInputDescriptor.binary());
        }
    }

    private BoundedDoclingClient client(HttpServer server, String apiKey) {
        return BoundedDoclingClient.create(new DoclingProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, Duration.ofSeconds(5), 200, null, null, false, apiKey));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
