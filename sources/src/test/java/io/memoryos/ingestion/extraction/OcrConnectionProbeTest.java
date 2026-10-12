package io.memoryos.ingestion.extraction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.ocr.OcrConnectionService;
import io.memoryos.ai.ocr.OcrPageReader;
import io.memoryos.ai.ocr.OcrProvider;
import io.memoryos.ai.ocr.OcrServiceUsage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** MEM-243: the connection test reads the bundled picture through the connection's adapter. */
class OcrConnectionProbeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final OcrPageReader pages = mock(OcrPageReader.class);
    private final OcrAdapterRegistry adapters = OcrAdapterRegistry.installed(
            new PaddleOcrVlProperties(Duration.ofSeconds(10), 200, null, null), pages, mock(OcrServiceUsage.class), mapper);
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void aServiceThatReadsThePictureTextPasses() throws Exception {
        serve(200, answer("MemoryOS OCR 2026"));
        assertTrue(new OcrConnectionProbe(adapters).reads(connection(), ""));
    }

    @Test
    void aServiceThatFailsOrReadsSomethingElseDoesNot() throws Exception {
        serve(503, "{\"errorCode\":503,\"errorMsg\":\"busy\"}");
        assertFalse(new OcrConnectionProbe(adapters).reads(connection(), ""));
        server.stop(0);
        serve(200, answer("Lorem ipsum"));
        assertFalse(new OcrConnectionProbe(adapters).reads(connection(), ""));
    }

    @Test
    void aModelConnectionPassesWhenItsModelReadsThePicture() {
        var connection = new OcrConnectionService.Connection(null, UUID.randomUUID(), OcrProvider.MODEL, "", null,
                DataBoundary.EXTERNAL, UUID.randomUUID(), 0);
        when(pages.read(eq(connection), any(), eq("image/png"))).thenReturn("MemoryOS OCR 2026").thenReturn("");

        assertTrue(new OcrConnectionProbe(adapters).reads(connection, ""));
        assertFalse(new OcrConnectionProbe(adapters).reads(connection, ""), "a model that returns nothing did not read it");
    }

    @Test
    void everyProviderHasExactlyOneAdapter() {
        assertThrows(IllegalStateException.class, () -> new OcrAdapterRegistry(List.of()));
        var paddle = new PaddleOcrVlOcrAdapter(new PaddleOcrVlProperties(null, 0, null, null), mapper);
        assertThrows(IllegalStateException.class, () -> new OcrAdapterRegistry(List.of(paddle, paddle)));
    }

    private OcrConnectionService.Connection connection() {
        return new OcrConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), OcrProvider.PADDLEOCR_VL,
                "http://127.0.0.1:" + server.getAddress().getPort(), null, DataBoundary.INTERNAL, null, 1);
    }

    private static String answer(String text) {
        return """
                {"logId":"probe","errorCode":0,"errorMsg":"Success","result":{"layoutParsingResults":[
                 {"prunedResult":{"width":720,"height":160,"parsing_res_list":[
                  {"block_label":"text","block_content":"%s","block_bbox":[40,45,680,115]}]}}]}}
                """.formatted(text);
    }

    private void serve(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/layout-parsing", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }
}
