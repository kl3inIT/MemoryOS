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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The clients of the types that speak {@code POST /systemone}, against a local server. The answer is the one 9Router
 * returned for {@code oc/jev-1.13-free} on 2026-10-04, with the field {@code cost} the protocol does not name.
 */
class SystemOneAdaptersTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String ANSWER = """
            {"model":"jev-1.13-free","answers":{"TOPIC_1":{"type":"noul","noul":0.26},
            "TOPIC_2":{"type":"noul","noul":0.86},"CONVERSATIONAL":{"type":"noul","noul":0.08}},
            "usage":{"input_tokens":506,"output_tokens":104},"cost":"0"}""";

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

    private static final String STATE = "Person: Thế còn vợ của chủ tịch nước là ai?";

    private static Map<String, Question> questions() {
        var questions = new LinkedHashMap<String, Question>();
        questions.put("TOPIC_1", Noul.of("Is the last Person message about this topic? Chính trị"));
        questions.put("TOPIC_2", Noul.builder().instructions("Is the last Person message about this topic? Lãnh tụ và lãnh đạo")
                .whenTrue("It is the case").whenFalse("Not the case").build());
        questions.put("CONVERSATIONAL", Noul.of("Is the last Person message small talk?"));
        return questions;
    }

    @Test void severalQuestionsGoInOneRequestAsTheProtocolDescribesAndEachAnswerIsRead() {
        answer(200, ANSWER);
        var response = new NineRouterSystemOneAdapter()
                .client(connection(SystemOneProvider.NINEROUTER, "oc/jev-1.13-free"), "test-secret", TIMEOUT)
                .systemOne(STATE, questions());

        assertEquals(0.86, response.noulValue("TOPIC_2"));
        assertEquals(0.26, response.noulValue("TOPIC_1"));
        assertEquals(0.08, response.noulValue("CONVERSATIONAL"));
        assertEquals(506, response.usage().inputTokens());
        assertEquals(104, response.usage().outputTokens());
        assertEquals("Bearer test-secret", authorization.get());
        JsonNode body = JsonMapper.shared().readTree(sent.get());
        assertEquals("oc/jev-1.13-free", body.path("model").asString());
        assertEquals(STATE, body.path("state").asString());
        assertEquals(List.of("TOPIC_1", "TOPIC_2", "CONVERSATIONAL"), List.copyOf(body.path("questions").propertyNames()));
        var topic = body.path("questions").path("TOPIC_2");
        assertEquals("noul", topic.path("type").asString());
        assertEquals("Is the last Person message about this topic? Lãnh tụ và lãnh đạo", topic.path("instructions").asString());
        assertEquals("It is the case", topic.path("criteria").path("true").asString());
    }

    @Test void aConnectionWithoutAKeySendsNoAuthorizationHeader() {
        answer(200, ANSWER);
        new LayaSystemOneAdapter().client(connection(SystemOneProvider.LAYA, "auto"), "", TIMEOUT).systemOne(STATE, questions());
        assertFalse(authorized.get());

        new CompatibleSystemOneAdapter().client(connection(SystemOneProvider.SYSTEMONE_COMPATIBLE, "clef-flash"),
                "serving-key", TIMEOUT).systemOne(STATE, questions());
        assertEquals("Bearer serving-key", authorization.get());
    }

    @Test void anAnswerWithoutUsageLeavesTheTokensUnknown() {
        answer(200, "{\"model\":\"auto\",\"answers\":{\"TOPIC_1\":{\"type\":\"noul\",\"noul\":0.5}}}");
        var response = new LayaSystemOneAdapter().client(connection(SystemOneProvider.LAYA, "auto"), "", TIMEOUT)
                .systemOne(STATE, questions());
        assertNull(response.usage().inputTokens());
        assertNull(response.usage().outputTokens());
    }

    @Test void aFailedStatusOrAnAnswerWithoutAnswersIsAFailure() {
        answer(401, "{\"error\":{\"message\":\"invalid key test-secret\"}}");
        var client = new CompatibleSystemOneAdapter()
                .client(connection(SystemOneProvider.SYSTEMONE_COMPATIBLE, "clef-flash"), "test-secret", TIMEOUT);
        var refused = assertThrows(RuntimeException.class, () -> client.systemOne(STATE, questions()));
        // The failed body is not read into the failure.
        assertFalse(String.valueOf(refused.getMessage()).contains("invalid key"));

        server.removeContext("/v1/systemone");
        answer(200, "{\"model\":\"clef-flash\",\"answers\":{}}");
        assertThrows(RuntimeException.class, () -> client.systemOne(STATE, questions()));
    }

    @Test void theHostedServiceHasOneAddressAndNeedsAKey() {
        var capabilities = new TypeSafeSystemOneAdapter().capabilities();
        assertTrue(capabilities.requiresKey());
        assertEquals(SystemOneCapabilities.Endpoint.FIXED, capabilities.endpoint());
        assertEquals("jev-latest", capabilities.defaultModel());
    }
}
