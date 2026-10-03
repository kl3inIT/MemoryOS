package io.memoryos.shared;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.shared.OutboundHttp.Limits;
import io.memoryos.shared.OutboundHttp.ResponseTooLargeException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

class OutboundHttpTest {
    private static final int BOUND = 1024;
    /** Long enough that a test which ends promptly did not end because of it. */
    private static final Duration PATIENT = Duration.ofSeconds(60);
    private static final Duration PROMPT = Duration.ofSeconds(10);

    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private final CountDownLatch released = new CountDownLatch(1);
    private HttpServer server;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.start();
    }

    @AfterEach void stop() {
        released.countDown();
        server.stop(0);
        handlers.shutdownNow();
    }

    private String url(String path) { return "http://localhost:" + server.getAddress().getPort() + path; }

    private static RestClient client(Duration timeout) { return OutboundHttp.builder(new Limits(timeout, BOUND)).build(); }

    /** Sends {@code status} and then a body that never ends; the latch opens once the client has gone away. */
    private CountDownLatch neverEnding(String path, int status) {
        var abandoned = new CountDownLatch(1);
        server.createContext(path, exchange -> {
            try (exchange; OutputStream body = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(status, 0);
                byte[] chunk = new byte[8192];
                while (true) {
                    body.write(chunk);
                    body.flush();
                }
            } catch (IOException gone) {
                abandoned.countDown();
            }
        });
        return abandoned;
    }

    private void answer(String path, int status, byte[] body, boolean declareLength) {
        server.createContext(path, exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(status, declareLength ? body.length : 0);
                exchange.getResponseBody().write(body);
            }
        });
    }

    @Test void aRedirectIsAFailureAndItsTargetIsNeverRequested() {
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
        var failure = assertThrows(RestClientResponseException.class,
                () -> client(PATIENT).get().uri(url("/moved")).header("Authorization", "Bearer secret").retrieve()
                        .body(String.class));
        assertEquals(302, failure.getStatusCode().value());
        assertEquals(0, followed.get());
    }

    @Test void aFailedAnswerIsReportedByStatusAndItsBodyIsNeverRead() throws Exception {
        var abandoned = neverEnding("/failed", 500);
        var failure = assertTimeoutPreemptively(PROMPT, () -> assertThrows(RestClientResponseException.class,
                () -> client(PATIENT).get().uri(url("/failed")).retrieve().body(String.class)));
        assertEquals(500, failure.getStatusCode().value());
        assertEquals(0, failure.getResponseBodyAsByteArray().length);
        assertTrue(abandoned.await(10, SECONDS), "the connection stays open while the failed body is sent");
    }

    @Test void aBodyAtTheBoundIsReadWhetherOrNotItsLengthIsDeclared() {
        byte[] body = new byte[BOUND];
        answer("/declared", 200, body, true);
        answer("/chunked", 200, body, false);
        assertArrayEquals(body, client(PATIENT).get().uri(url("/declared")).retrieve().body(byte[].class));
        assertArrayEquals(body, client(PATIENT).get().uri(url("/chunked")).retrieve().body(byte[].class));
    }

    @Test void aBodyOverTheBoundFailsWhetherOrNotItsLengthIsDeclared() {
        byte[] body = new byte[BOUND + 1];
        answer("/declared", 200, body, true);
        answer("/chunked", 200, body, false);
        for (String path : new String[]{"/declared", "/chunked"}) {
            var failure = assertThrows(RestClientException.class,
                    () -> client(PATIENT).get().uri(url(path)).retrieve().body(byte[].class), path);
            assertInstanceOf(ResponseTooLargeException.class, NestedExceptionUtils.getRootCause(failure), path);
        }
    }

    @Test void aBodyThatNeverEndsStopsBeingReadAtTheBound() throws Exception {
        var abandoned = neverEnding("/endless", 200);
        var failure = assertTimeoutPreemptively(PROMPT, () -> assertThrows(RestClientException.class,
                () -> client(PATIENT).get().uri(url("/endless")).retrieve().body(byte[].class)));
        assertInstanceOf(ResponseTooLargeException.class, NestedExceptionUtils.getRootCause(failure));
        assertTrue(abandoned.await(10, SECONDS), "the connection stays open while the body is sent");
    }

    @Test void aResponseTheCallerStopsReadingIsClosedWithoutReadingTheRest() throws Exception {
        var abandoned = neverEnding("/listing", 200);
        byte[] start = assertTimeoutPreemptively(PROMPT, () -> client(PATIENT).get().uri(url("/listing"))
                .exchange((request, response) -> response.getBody().readNBytes(256)));
        assertEquals(256, start.length);
        assertTrue(abandoned.await(10, SECONDS), "the connection stays open while the body is sent");
    }

    @Test void aBodyThatStallsEndsAtTheDeadline() {
        server.createContext("/stalled", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(new byte[16]);
                exchange.getResponseBody().flush();
                released.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTimeoutPreemptively(PROMPT, () -> assertThrows(RestClientException.class,
                () -> client(Duration.ofSeconds(1)).get().uri(url("/stalled")).retrieve().body(byte[].class)));
    }

    @Test void anUploadIsSentAsItIsProduced() {
        var firstPartRead = new CountDownLatch(1);
        var received = new AtomicLong();
        server.createContext("/upload", exchange -> {
            try (exchange) {
                // Not readNBytes: its closing zero-length read makes this server wait for the next chunk.
                long first = 0;
                byte[] buffer = new byte[1024];
                while (first < 8192) {
                    int read = exchange.getRequestBody().read(buffer, 0, (int) Math.min(buffer.length, 8192 - first));
                    if (read < 0) break;
                    first += read;
                }
                firstPartRead.countDown();
                received.set(first + exchange.getRequestBody().transferTo(OutputStream.nullOutputStream()));
                exchange.sendResponseHeaders(204, -1);
            }
        });
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> client(PATIENT).post().uri(url("/upload")).body(body -> {
            body.write(new byte[8192]);
            body.flush();
            try {
                // A client that held the body in memory would send nothing until this method returned.
                if (!firstPartRead.await(10, SECONDS)) throw new IOException("Nothing was sent before the body ended");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            body.write(new byte[8192]);
        }).retrieve().toBodilessEntity());
        assertEquals(16384, received.get());
    }

    @HttpExchange(accept = MediaType.APPLICATION_JSON_VALUE)
    interface Files {
        @PostExchange(url = "/folders/{folder}/files", contentType = MediaType.MULTIPART_FORM_DATA_VALUE)
        Stored store(@PathVariable String folder, @RequestPart("file") HttpEntity<Resource> file);

        record Stored(String id) {}
    }

    @Test void anExchangeInterfaceSendsItsPartUnderTheClientsBaseUrlAndHeaders() {
        var target = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var sent = new AtomicReference<String>();
        server.createContext("/v1/folders", exchange -> {
            try (exchange) {
                target.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getRawPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
                byte[] answer = "{\"id\":\"file-1\",\"ignored\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, answer.length);
                exchange.getResponseBody().write(answer);
            }
        });
        var api = OutboundHttp.service(Files.class, OutboundHttp.builder(new Limits(PATIENT, BOUND)).baseUrl(url("/v1"))
                .defaultHeaders(headers -> headers.setBearerAuth("secret")).build());
        var part = new HttpHeaders();
        part.setContentType(MediaType.parseMediaType("audio/mp4"));
        var clip = new ByteArrayResource(new byte[] {1, 2, 3}) {
            @Override public String getFilename() { return "clip.m4a"; }
        };
        assertEquals("file-1", api.store("a b", new HttpEntity<>(clip, part)).id());
        assertEquals("POST /v1/folders/a%20b/files", target.get());
        assertEquals("Bearer secret", authorization.get());
        assertTrue(sent.get().contains("Content-Disposition: form-data; name=\"file\"; filename=\"clip.m4a\""), sent.get());
        assertTrue(sent.get().contains("Content-Type: audio/mp4"), sent.get());
    }

    @Test void jsonIsReadFromAnAnswerThatDeclaresNoContentTypeOrTheWrongOne() {
        byte[] json = "{\"id\":\"file-1\"}".getBytes(StandardCharsets.UTF_8);
        answer("/undeclared", 200, json, true);
        server.createContext("/mislabelled", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, json.length);
                exchange.getResponseBody().write(json);
            }
        });
        for (String path : new String[]{"/undeclared", "/mislabelled"}) {
            JsonNode answer = client(PATIENT).get().uri(url(path)).retrieve().body(JsonNode.class);
            assertEquals("file-1", answer.path("id").asString(), path);
        }
    }

    @Test void jsonIsWrittenAndReadAndNoCompressionIsAskedFor() {
        var sent = new AtomicReference<String>();
        var acceptEncoding = new AtomicReference<String>();
        server.createContext("/json", exchange -> {
            try (exchange) {
                sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                acceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
                byte[] answer = "{\"text\":\"xin chào\",\"extra\":1}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, answer.length);
                exchange.getResponseBody().write(answer);
            }
        });
        JsonNode answer = client(PATIENT).post().uri(url("/json")).body(Map.of("model", "tên")).retrieve()
                .body(JsonNode.class);
        assertEquals("{\"model\":\"tên\"}", sent.get());
        assertEquals("xin chào", answer.path("text").asString());
        assertEquals(null, acceptEncoding.get());
    }
}
