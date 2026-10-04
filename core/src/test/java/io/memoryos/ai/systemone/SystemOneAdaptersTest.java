package io.memoryos.ai.systemone;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.systemone.adapter.CompatibleSystemOneAdapter;
import io.memoryos.ai.systemone.adapter.LayaSystemOneAdapter;
import io.memoryos.ai.systemone.adapter.NineRouterSystemOneAdapter;
import io.memoryos.ai.systemone.adapter.TypeSafeSystemOneAdapter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The adapters that speak {@code POST /systemone}, against a local server that answers as the protocol describes. */
class SystemOneAdaptersTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String ANSWER = """
            {"model":"jev-1.13","answers":{"decision":{"type":"choice","choice":"TOPIC_1",
            "probabilities":{"TOPIC_1":0.91,"QUESTION":0.09},"confidence":0.82}},
            "usage":{"input_tokens":212,"output_tokens":0}}""";

    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicBoolean authorized = new AtomicBoolean();
    private final AtomicReference<String> sent = new AtomicReference<>();
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

    private void answer(int status, String body) {
        server.createContext("/v1/systemone", exchange -> {
            try (exchange) {
                authorized.set(exchange.getRequestHeaders().containsKey("Authorization"));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                sent.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
                byte[] bytes = body.getBytes(UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
    }

    private SystemOneConnectionService.Connection connection(SystemOneProvider provider, String model) {
        return new SystemOneConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), provider, "Serving",
                "http://localhost:" + server.getAddress().getPort() + "/v1/", model, null, DataBoundary.INTERNAL, null);
    }

    private static SystemOneClient.Question question() {
        var options = new LinkedHashMap<String, String>();
        options.put("TOPIC_1", "Chính trị: câu hỏi về đảng phái và bầu cử.");
        options.put("QUESTION", "Anything else.");
        return new SystemOneClient.Question("Person: Đảng nào tốt hơn?", "Classify the last Person message.", options);
    }

    @Test void aChoiceIsSentAsTheProtocolDescribesAndItsAnswerRead() {
        answer(200, ANSWER);
        var decision = new NineRouterSystemOneAdapter().choose(
                connection(SystemOneProvider.NINEROUTER, "openrouter/typesafe/jev-1.13"), "test-secret", question(), TIMEOUT);

        assertEquals("TOPIC_1", decision.label());
        assertEquals(0.82, decision.confidence());
        assertEquals(0.91, decision.probabilities().get("TOPIC_1"));
        assertEquals(212L, decision.inputTokens());
        assertEquals(0L, decision.outputTokens());
        assertEquals("Bearer test-secret", authorization.get());
        JsonNode body = JsonMapper.shared().readTree(sent.get());
        assertEquals("openrouter/typesafe/jev-1.13", body.path("model").asString());
        assertEquals("Person: Đảng nào tốt hơn?", body.path("state").asString());
        var choice = body.path("questions").path("decision");
        assertEquals("choice", choice.path("type").asString());
        assertEquals("Classify the last Person message.", choice.path("instructions").asString());
        assertEquals(List.of("TOPIC_1", "QUESTION"), List.copyOf(choice.path("criteria").propertyNames()));
        assertEquals("Anything else.", choice.path("criteria").path("QUESTION").asString());
    }

    @Test void aConnectionWithoutAKeySendsNoAuthorizationHeader() {
        answer(200, ANSWER);
        new LayaSystemOneAdapter().choose(connection(SystemOneProvider.LAYA, "auto"), "", question(), TIMEOUT);
        assertFalse(authorized.get());

        new CompatibleSystemOneAdapter().choose(connection(SystemOneProvider.SYSTEMONE_COMPATIBLE, "clef-flash"),
                "serving-key", question(), TIMEOUT);
        assertEquals("Bearer serving-key", authorization.get());
    }

    @Test void anAnswerWithoutUsageLeavesTheTokensUnknown() {
        answer(200, """
                {"model":"auto","answers":{"decision":{"type":"choice","choice":"QUESTION",
                "probabilities":{"QUESTION":1.0},"confidence":1.0}}}""");
        var decision = new LayaSystemOneAdapter().choose(connection(SystemOneProvider.LAYA, "auto"), "", question(), TIMEOUT);
        assertEquals("QUESTION", decision.label());
        assertNull(decision.inputTokens());
        assertNull(decision.outputTokens());
    }

    @Test void aFailedStatusOrAnAnswerWithoutTheChoiceIsAFailure() {
        answer(401, "{\"error\":{\"message\":\"invalid key test-secret\"}}");
        var adapter = new CompatibleSystemOneAdapter();
        var connection = connection(SystemOneProvider.SYSTEMONE_COMPATIBLE, "clef-flash");
        var refused = assertThrows(RuntimeException.class, () -> adapter.choose(connection, "test-secret", question(), TIMEOUT));
        // The failed body is not read into the failure.
        assertFalse(String.valueOf(refused.getMessage()).contains("invalid key"));

        server.removeContext("/v1/systemone");
        answer(200, "{\"model\":\"clef-flash\",\"answers\":{\"other\":{\"type\":\"noul\",\"noul\":0.4}}}");
        assertThrows(RuntimeException.class, () -> adapter.choose(connection, "test-secret", question(), TIMEOUT));
    }

    @Test void theHostedServiceHasOneAddressAndNeedsAKey() {
        var capabilities = new TypeSafeSystemOneAdapter().capabilities();
        assertTrue(capabilities.requiresKey());
        assertEquals(SystemOneCapabilities.Endpoint.FIXED, capabilities.endpoint());
        assertEquals("jev-latest", capabilities.defaultModel());
    }
}
