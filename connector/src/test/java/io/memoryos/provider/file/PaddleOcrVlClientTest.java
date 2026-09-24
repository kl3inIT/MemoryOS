package io.memoryos.provider.file;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.ingestion.ExtractionException;
import io.memoryos.ingestion.ExtractionFailure;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** MEM-192. The PaddleOCR-VL request as sent over a loopback socket, and each way it can fail. */
class PaddleOcrVlClientTest {
    private static final String ANSWER = """
            {"logId":"8f0c2c1e-4d3a","errorCode":0,"errorMsg":"Success",
             "result":{"layoutParsingResults":[{"prunedResult":{"width":10,"height":10,"parsing_res_list":[]},
             "markdown":{"text":""}}],"dataInfo":{"type":"pdf","numPages":1}}}
            """;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LinkedBlockingQueue<Captured> requests = new LinkedBlockingQueue<>();
    private HttpServer server;

    @TempDir Path temporary;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void aFileTravelsAsStreamedBase64WithTheFixedParameters() throws Exception {
        // Not a multiple of three and larger than one encoding block, so padding and block joins both count.
        byte[] document = new byte[3 * 16_384 * 4 + 2];
        new Random(192).nextBytes(document);
        Path file = temporary.resolve("scan.pdf");
        Files.write(file, document);
        serve(200, ANSWER);
        try (var client = client(Duration.ofSeconds(10))) {
            var results = client.parse(PaddleOcrVlClient.Input.of(file), PaddleOcrVlClient.FileType.PDF);
            assertEquals(1, results.size());
            client.parse(PaddleOcrVlClient.Input.of(new byte[] {1, 2, 3, 4}), PaddleOcrVlClient.FileType.IMAGE);
        }

        var pdf = requests.poll(5, TimeUnit.SECONDS);
        assertNotNull(pdf);
        assertEquals("/layout-parsing", pdf.path());
        assertEquals("POST", pdf.method());
        assertEquals("application/json", pdf.contentType());
        assertEquals(String.valueOf(pdf.body().length), pdf.contentLength(), "a known length, not a chunked body");
        var body = mapper.readTree(pdf.body());
        assertEquals(0, body.path("fileType").asInt());
        assertFalse(body.path("visualize").asBoolean(true));
        assertFalse(body.path("mergeTables").asBoolean(true));
        assertFalse(body.path("useDocOrientationClassify").asBoolean(true));
        assertFalse(body.path("useDocUnwarping").asBoolean(true));
        assertEquals(6, body.size(), "no key, no other option");
        assertArrayEquals(document, Base64.getDecoder().decode(body.path("file").asString()));

        var image = mapper.readTree(requests.poll(5, TimeUnit.SECONDS).body());
        assertEquals(1, image.path("fileType").asInt());
        assertArrayEquals(new byte[] {1, 2, 3, 4}, Base64.getDecoder().decode(image.path("file").asString()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 49_151, 49_152, 49_153, 98_305})
    void theEncodingStreamMatchesTheStandardEncoder(int size) throws IOException {
        byte[] source = new byte[size];
        new Random(size).nextBytes(source);
        try (var encoded = new PaddleOcrVlClient.Base64Stream(new ByteArrayInputStream(source))) {
            byte[] expected = Base64.getEncoder().encode(source);
            assertArrayEquals(expected, encoded.readAllBytes());
            assertEquals(expected.length, PaddleOcrVlClient.Base64Stream.encodedLength(size));
        }
    }

    @ParameterizedTest
    @CsvSource({"413,WRITE_LIMIT", "408,TIMEOUT", "504,TIMEOUT", "500,INTERNAL", "503,INTERNAL", "400,INTERNAL"})
    void anHttpFailureMapsOntoAnExtractionFailure(int status, ExtractionFailure expected) throws Exception {
        serve(status, "{\"errorCode\":" + status + ",\"errorMsg\":\"document text must not reach the log\"}");
        assertFailure(expected, client(Duration.ofSeconds(10)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "",
            "{\"errorCode\":500,\"errorMsg\":\"failed\",\"result\":{\"layoutParsingResults\":[{}]}}",
            "{\"errorCode\":0,\"errorMsg\":\"Success\"}",
            "{\"errorCode\":0,\"result\":{\"layoutParsingResults\":[]}}",
            "{\"errorCode\":\"0\",\"result\":{\"layoutParsingResults\":[{}]}}"})
    void anAnswerThatIsNotALayoutIsMalformed(String answer) throws Exception {
        serve(200, answer);
        assertFailure(ExtractionFailure.MALFORMED, client(Duration.ofSeconds(10)));
    }

    @Test
    void anAnswerOverTheCapIsAWriteLimit() throws Exception {
        serve(200, ANSWER);
        var properties = new PaddleOcrVlProperties(endpoint(), Duration.ofSeconds(10), 200, null, null);
        try (var capped = new PaddleOcrVlClient(properties, mapper, ANSWER.getBytes(StandardCharsets.UTF_8).length - 1)) {
            assertFailure(ExtractionFailure.WRITE_LIMIT, capped);
        }
        try (var exact = new PaddleOcrVlClient(properties, mapper, ANSWER.getBytes(StandardCharsets.UTF_8).length)) {
            assertEquals(1, exact.parse(PaddleOcrVlClient.Input.of(new byte[] {1}), PaddleOcrVlClient.FileType.PDF).size());
        }
    }

    @Test
    void anUnreachableServiceIsAConnectionFailure() throws Exception {
        serve(200, ANSWER);
        var closed = endpoint();
        server.stop(0);
        server = null;
        try (var client = new PaddleOcrVlClient(new PaddleOcrVlProperties(closed, Duration.ofSeconds(10), 200, null, null), mapper)) {
            assertFailure(ExtractionFailure.CONNECTION_FAILED, client);
        }
    }

    @Test
    void aServiceSlowerThanTheTimeoutIsATimeout() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/layout-parsing", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                Thread.sleep(3_000);
                respond(exchange, 200, ANSWER);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        assertFailure(ExtractionFailure.TIMEOUT, client(Duration.ofMillis(300)));
    }

    @Test
    void aBodyThatStallsAfterTheHeadersIsATimeoutAtTheSameDeadline() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/layout-parsing", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = ANSWER.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes, 0, bytes.length / 2);
                exchange.getResponseBody().flush();
                Thread.sleep(10_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        long started = System.nanoTime();
        assertFailure(ExtractionFailure.TIMEOUT, client(Duration.ofMillis(500)));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0,
                "released by the deadline, not by the server finishing its body");
    }

    @Test
    void anInterruptedCallerIsATimeoutAndStaysInterrupted() throws Exception {
        serve(200, ANSWER);
        try (var client = client(Duration.ofSeconds(10))) {
            Thread.currentThread().interrupt();
            try {
                assertFailure(ExtractionFailure.TIMEOUT, client);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
        assertNull(requests.poll(), "nothing is sent for an interrupted caller");
    }

    @Test
    void aRequestWaitingForAPermitIsNotTimedOutByItsWait() throws Exception {
        var inFlight = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        serveSlowly(Duration.ofMillis(1_500), inFlight, peak);
        var properties = new PaddleOcrVlProperties(endpoint(), Duration.ofMillis(2_500), 200, null, 1);
        try (var client = new PaddleOcrVlClient(properties, mapper);
                var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            long started = System.nanoTime();
            var first = callers.submit(() -> client.parse(PaddleOcrVlClient.Input.of(new byte[] {1}), PaddleOcrVlClient.FileType.PDF));
            var second = callers.submit(() -> client.parse(PaddleOcrVlClient.Input.of(new byte[] {2}), PaddleOcrVlClient.FileType.PDF));

            assertEquals(1, first.get(10, TimeUnit.SECONDS).size());
            assertEquals(1, second.get(10, TimeUnit.SECONDS).size(), "the second waited 1.5 s and still had 2.5 s once sent");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofMillis(3_000)) >= 0,
                    "one after the other, past the timeout either request has");
        }
        assertEquals(1, peak.get());
    }

    @Test
    void theServiceNeverHasMoreRequestsThanTheBound() throws Exception {
        var inFlight = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        serveSlowly(Duration.ofMillis(300), inFlight, peak);
        var properties = new PaddleOcrVlProperties(endpoint(), Duration.ofSeconds(10), 200, null, 2);
        try (var client = new PaddleOcrVlClient(properties, mapper);
                var callers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int call = 0; call < 6; call++) {
                calls.add(callers.submit(() -> client.parse(PaddleOcrVlClient.Input.of(new byte[] {1}), PaddleOcrVlClient.FileType.PDF)));
            }
            for (var call : calls) call.get(10, TimeUnit.SECONDS);
        }
        assertEquals(2, peak.get(), "two at once, never a third");
    }

    @Test
    void anEndpointWithATrailingSlashStillNamesTheOperation() throws Exception {
        serve(200, ANSWER);
        var properties = new PaddleOcrVlProperties(URI.create(endpoint() + "/"), Duration.ofSeconds(10), 200, null, null);
        try (var client = new PaddleOcrVlClient(properties, mapper)) {
            client.parse(PaddleOcrVlClient.Input.of(new byte[] {1}), PaddleOcrVlClient.FileType.PDF);
        }
        assertEquals("/layout-parsing", requests.poll(5, TimeUnit.SECONDS).path());
    }

    private void assertFailure(ExtractionFailure expected, PaddleOcrVlClient client) {
        try (client) {
            var error = assertThrows(ExtractionException.class,
                    () -> client.parse(PaddleOcrVlClient.Input.of(new byte[] {1, 2, 3}), PaddleOcrVlClient.FileType.PDF));
            assertEquals(expected, error.failure());
            assertFalse(error.getMessage().contains("document text"));
        }
    }

    private PaddleOcrVlClient client(Duration timeout) {
        return new PaddleOcrVlClient(new PaddleOcrVlProperties(endpoint(), timeout, 200, null, null), mapper);
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private void serve(int status, String answer) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(new Captured(exchange.getRequestURI().getPath(), exchange.getRequestMethod(),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        exchange.getRequestHeaders().getFirst("Content-Length"), exchange.getRequestBody().readAllBytes()));
                respond(exchange, status, answer);
            }
        });
        server.start();
    }

    /** Answers every request after {@code hold}, on a thread of its own, counting the requests open at once. */
    private void serveSlowly(Duration hold, java.util.concurrent.atomic.AtomicInteger inFlight,
            java.util.concurrent.atomic.AtomicInteger peak) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/layout-parsing", exchange -> {
            try (exchange) {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    exchange.getRequestBody().readAllBytes();
                    Thread.sleep(hold);
                } finally {
                    // Before the answer leaves, so the next request in line cannot overlap this count.
                    inFlight.decrementAndGet();
                }
                respond(exchange, 200, ANSWER);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String answer) throws IOException {
        byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
    }

    private record Captured(String path, String method, String contentType, String contentLength, byte[] body) {}
}
