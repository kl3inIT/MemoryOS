package io.memoryos.ai.systemone.adapter;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.ai.systemone.SystemOneProvider;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.typesafe.question.Noul;
import org.springaicommunity.typesafe.question.Question;
import tools.jackson.databind.json.JsonMapper;

/** Workers AI as its published schemas describe it; whether the answer is wrapped is not verified, so both are read. */
class CloudflareSystemOneAdapterTest {
    private static final String ACCOUNT = "3f9a1c0b7d2e4a56b8c9d0e1f2a3b4c5";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final String STATE = "Person: Quy trình nghỉ phép thế nào?";
    private static final Map<String, Question> QUESTIONS = Map.of("CONVERSATIONAL", Noul.of("Is the message small talk?"));
    private static final String ANSWER = """
            {"model":"clef-flash","answers":{"CONVERSATIONAL":{"type":"noul","noul":0.12}},
            "usage":{"input_tokens":57,"output_tokens":0}}""";

    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> sent = new AtomicReference<>();
    private HttpServer server;
    private CloudflareSystemOneAdapter adapter;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(handlers);
        server.start();
        adapter = new CloudflareSystemOneAdapter("http://localhost:" + server.getAddress().getPort() + "/client/v4/accounts");
    }

    @AfterEach void stop() {
        server.stop(0);
        handlers.shutdownNow();
    }

    private void answer(int status, String body) {
        server.createContext("/", exchange -> {
            try (exchange) {
                path.set(exchange.getRequestURI().getRawPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                sent.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
                byte[] bytes = body.getBytes(UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
    }

    private static SystemOneConnectionService.Connection connection(String model) {
        return new SystemOneConnectionService.Connection(UUID.randomUUID(), UUID.randomUUID(), SystemOneProvider.CLOUDFLARE,
                "Cloudflare", ACCOUNT, model, "v1:stored", DataBoundary.EXTERNAL, 0.09);
    }

    @Test void theAddressNamesTheAccountAndTheModelAndTheBodyNamesTheModelWithoutItsNamespace() {
        answer(200, ANSWER);
        var response = adapter.client(connection("@cf/cloudflare/clef-flash"), "cf-token", TIMEOUT).systemOne(STATE, QUESTIONS);

        assertEquals("/client/v4/accounts/" + ACCOUNT + "/ai/run/@cf/cloudflare/clef-flash", path.get());
        assertEquals("Bearer cf-token", authorization.get());
        var body = JsonMapper.shared().readTree(sent.get());
        assertEquals("clef-flash", body.path("model").asString());
        assertEquals("noul", body.path("questions").path("CONVERSATIONAL").path("type").asString());
        assertEquals(0.12, response.noulValue("CONVERSATIONAL"));
        assertEquals(57, response.usage().inputTokens());
    }

    @Test void anAnswerInsideTheWorkersAiEnvelopeIsReadTheSame() {
        answer(200, "{\"result\":" + ANSWER + ",\"success\":true,\"errors\":[],\"messages\":[]}");
        var response = adapter.client(connection("clef"), "cf-token", TIMEOUT).systemOne(STATE, QUESTIONS);

        assertEquals("/client/v4/accounts/" + ACCOUNT + "/ai/run/@cf/cloudflare/clef", path.get());
        assertEquals(0.12, response.noulValue("CONVERSATIONAL"));
        assertEquals(57, response.usage().inputTokens());
    }

    @Test void aRefusedTokenOrAnEnvelopeWithoutAnAnswerIsAFailure() {
        answer(403, "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"Authentication error\"}]}");
        var client = adapter.client(connection("clef-flash"), "cf-token", TIMEOUT);
        assertThrows(RuntimeException.class, () -> client.systemOne(STATE, QUESTIONS));

        server.removeContext("/");
        answer(200, "{\"result\":null,\"success\":false,\"errors\":[{\"code\":5007,\"message\":\"No such model\"}]}");
        assertThrows(RuntimeException.class, () -> client.systemOne(STATE, QUESTIONS));
    }
}
