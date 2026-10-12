package io.memoryos.ai.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.ai.AiException;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.LlmProvider;
import io.memoryos.ai.ModelClients;
import io.memoryos.ai.ModelConfiguration;
import io.memoryos.ai.ModelLeases;
import io.memoryos.ai.ModelSettings;
import io.memoryos.ai.ProviderAdapterRegistry;
import io.memoryos.ai.ProviderCredentials;
import io.memoryos.ai.anthropic.AnthropicProviderConfiguration;
import io.memoryos.usage.AiUsage;
import io.memoryos.usage.AiUsageFlow;
import io.memoryos.usage.AiUsageRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** MEM-243: one page picture is read by the connection's model through its provider adapter, and counted. */
class OcrPageReaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID tenant = UUID.randomUUID();
    private final List<JsonNode> received = new CopyOnWriteArrayList<>();
    private final OcrConnectionService connections = mock(OcrConnectionService.class);
    private final ProviderCredentials credentials = mock(ProviderCredentials.class);
    private final AiUsageRecorder usage = mock(AiUsageRecorder.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ModelClients clients = new ModelClients(2);
    private HttpServer server;
    private OcrPageReader reader;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void serve() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            try (exchange) {
                received.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
                byte[] body = """
                        {"id":"msg_1","type":"message","role":"assistant","model":"claude-fixture",
                        "content":[{"type":"text","text":" Tổng cộng tài sản 12.345.678 \\n"}],"stop_reason":"end_turn",
                        "stop_sequence":null,"usage":{"input_tokens":1500,"output_tokens":200}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        when(credentials.resolve(any(), any(), any())).thenReturn("fixture-only");
        var adapter = new AnthropicProviderConfiguration().anthropicChatProviderAdapter(ObservationRegistry.NOOP, meters);
        var leases = new ModelLeases(new ProviderAdapterRegistry(List.of(adapter)), credentials, clients, Duration.ofSeconds(5));
        ObjectProvider<AiUsageRecorder> recorder = mock(ObjectProvider.class);
        when(recorder.getIfAvailable()).thenReturn(usage);
        reader = new OcrPageReader(connections, leases, recorder);
    }

    @AfterEach
    void stop() {
        clients.close();
        meters.close();
        server.stop(0);
    }

    @Test
    void aPageIsSentAsAPictureWithTheTranscriptionInstructionsAndItsUsageIsRecorded() {
        var connection = connection(true);
        byte[] picture = {1, 2, 3, 4};

        assertEquals("Tổng cộng tài sản 12.345.678", reader.read(connection, picture, "image/jpeg"));

        var request = received.getFirst();
        assertTrue(request.path("system").toString().contains("exactly as written"));
        assertTrue(request.path("max_tokens").asInt() <= OcrPageReader.MAX_OUTPUT_TOKENS);
        assertFalse(request.has("tools"));
        assertFalse("enabled".equals(request.path("thinking").path("type").asString("")), "a page is read without thinking");
        var image = request.path("messages").get(0).path("content").findParent("source").path("source");
        assertEquals("image/jpeg", image.path("media_type").asString());
        assertEquals(Base64.getEncoder().encodeToString(picture), image.path("data").asString());

        var recorded = ArgumentCaptor.forClass(AiUsage.class);
        verify(usage).record(recorded.capture());
        assertEquals(AiUsageFlow.OCR, recorded.getValue().flow());
        assertEquals(tenant, recorded.getValue().tenant());
        assertEquals(1500, recorded.getValue().inputTokens());
        assertEquals(200, recorded.getValue().outputTokens());
        assertEquals((1500 * 1.0 + 200 * 5.0) / 1_000_000, recorded.getValue().cost(), 1e-12);
        assertEquals("EXTERNAL", recorded.getValue().dataBoundary());
    }

    @Test
    void aModelThatDoesNotReadImagesIsRefusedBeforeItIsCalled() {
        var connection = connection(false);

        assertThrows(AiException.class, () -> reader.read(connection, new byte[] {1}, "image/jpeg"));
        assertTrue(received.isEmpty());
    }

    private OcrConnectionService.Connection connection(boolean vision) {
        var provider = new LlmProvider(UUID.randomUUID(), tenant, "Claude", "anthropic",
                "http://127.0.0.1:" + server.getAddress().getPort(), true, true, "v1:stored", 1, Set.of(), Set.of(),
                DataBoundary.EXTERNAL);
        var model = new ModelConfiguration(UUID.randomUUID(), tenant, provider.id(), "claude-fixture", "Claude fixture", true,
                new ModelSettings(200_000, 8192, new ModelSettings.Capabilities(true, true, vision, true, false), Map.of(),
                        new ModelSettings.Pricing(1.0, 5.0), "openai-o200k-v1"), 1);
        var connection = new OcrConnectionService.Connection(UUID.randomUUID(), tenant, OcrProvider.MODEL, "", null,
                DataBoundary.EXTERNAL, model.id(), 1);
        when(connections.model(connection)).thenReturn(new OcrConnectionService.ModelSelection(provider, model));
        return connection;
    }
}
