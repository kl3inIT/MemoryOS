package io.memoryos.ingestion.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrProvider;
import io.memoryos.ai.ocr.OcrServiceUsage;
import io.memoryos.document.ExtractionException;
import io.memoryos.document.ExtractionFailure;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** MEM-243: AI Hay reads one picture a call; a failure's kind comes from the status, and each page read is counted. */
class AiHayOcrAdapterTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private final ObjectMapper mapper = new ObjectMapper();
    private final OcrServiceUsage usage = mock(OcrServiceUsage.class);
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private volatile String answer = "{\"result\":{\"text\":\" Hoá đơn số 42 \"}}";
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void aPictureIsPostedWithTheKeyAndItsTextIsThePagesText() throws Exception {
        var connection = serve();
        try (var reader = reader(connection)) {
            var result = reader.read(PaddleOcrVlClient.Input.of(PNG), null, "bill.png", "image/png", 1_000_000);

            assertEquals("Hoá đơn số 42", result.normalizedText().strip());
            assertEquals("aihay-ocr", result.metadata().get("parser"));
            assertEquals("Bearer secret-key", keys.getFirst());
            assertEquals(Base64.getEncoder().encodeToString(PNG), bodies.getFirst().path("image").asString());
            verify(usage, times(1)).page(connection);
        }
    }

    @Test
    void aPictureTheServiceWillNotReadIsAPageWithoutText() throws Exception {
        var connection = serve();
        status.set(413);
        try (var reader = reader(connection)) {
            assertNull(reader.read(PaddleOcrVlClient.Input.of(PNG), null, "bill.png", "image/png", 1_000_000));
        }
    }

    @Test
    void aBusyServiceIsRetriedAndARefusedKeyOrAnotherAnswerIsNot() throws Exception {
        var connection = serve();
        try (var reader = reader(connection)) {
            for (int busy : new int[] {429, 500, 503}) {
                status.set(busy);
                assertEquals(ExtractionFailure.CONNECTION_FAILED, failure(reader), "status " + busy);
            }
            for (int refused : new int[] {401, 403, 302, 404}) {
                status.set(refused);
                assertEquals(ExtractionFailure.INTERNAL, failure(reader), "status " + refused);
            }
            status.set(200);
            answer = "<html>not the API</html>";
            assertEquals(ExtractionFailure.INTERNAL, failure(reader));
            verify(usage, never()).page(connection);
        }
        assertTrue(ExtractionFailure.CONNECTION_FAILED.retryable());
    }

    @Test
    void aServiceThatCannotBeReachedIsRetried() throws Exception {
        var connection = serve();
        server.stop(0);
        server = null;
        try (var reader = reader(connection)) {
            assertEquals(ExtractionFailure.CONNECTION_FAILED, failure(reader));
        }
    }

    private OcrReader reader(OcrConnectionService.Connection connection) {
        return new AiHayOcrAdapter(usage, Duration.ofSeconds(5), mapper).reader(connection, "secret-key");
    }

    private static ExtractionFailure failure(OcrReader reader) {
        return assertThrows(ExtractionException.class,
                () -> reader.read(PaddleOcrVlClient.Input.of(PNG), null, "bill.png", "image/png", 1_000_000)).failure();
    }

    /** The connection's address ends in {@code /v1}, as an administrator may paste it; the adapter posts to {@code /v1/ocr}. */
    private OcrConnectionService.Connection serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/ocr", exchange -> {
            try (exchange) {
                keys.add(exchange.getRequestHeaders().getFirst("Authorization"));
                bodies.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                if (status.get() == 302) exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        return new OcrConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), OcrProvider.AIHAY,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", "v1:stored", DataBoundary.EXTERNAL, null, 1);
    }
}
