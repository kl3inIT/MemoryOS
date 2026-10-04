package io.memoryos.ai.systemone.adapter;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import io.memoryos.ai.DataBoundary;
import io.memoryos.ai.systemone.SystemOneClient;
import io.memoryos.ai.systemone.SystemOneConnectionService;
import io.memoryos.ai.systemone.SystemOneProvider;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Workers AI as its published schemas describe it; whether the answer is wrapped is not verified, so both are read. */
class CloudflareSystemOneAdapterTest {
    private static final String ACCOUNT = "3f9a1c0b7d2e4a56b8c9d0e1f2a3b4c5";
    private static final String ANSWER = """
            {"model":"clef-flash","answers":{"decision":{"type":"choice","choice":"QUESTION",
            "probabilities":{"CONVERSATIONAL":0.2,"QUESTION":0.8},"confidence":0.6}},
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

    private static SystemOneClient.Question question() {
        var options = new LinkedHashMap<String, String>();
        options.put("CONVERSATIONAL", "Small talk.");
        options.put("QUESTION", "Anything else.");
        return new SystemOneClient.Question("Person: Quy trình nghỉ phép thế nào?", "Classify the last Person message.", options);
    }

    @Test void theAddressNamesTheAccountAndTheModelAndTheBodyNamesTheModelWithoutItsNamespace() {
        answer(200, ANSWER);
        var decision = adapter.choose(connection("@cf/cloudflare/clef-flash"), "cf-token", question(), Duration.ofSeconds(5));

        assertEquals("/client/v4/accounts/" + ACCOUNT + "/ai/run/@cf/cloudflare/clef-flash", path.get());
        assertEquals("Bearer cf-token", authorization.get());
        var body = JsonMapper.shared().readTree(sent.get());
        assertEquals("clef-flash", body.path("model").asString());
        assertEquals("choice", body.path("questions").path("decision").path("type").asString());
        assertEquals("QUESTION", decision.label());
        assertEquals(0.6, decision.confidence());
        assertEquals(57L, decision.inputTokens());
    }

    @Test void anAnswerInsideTheWorkersAiEnvelopeIsReadTheSame() {
        answer(200, "{\"result\":" + ANSWER + ",\"success\":true,\"errors\":[],\"messages\":[]}");
        var decision = adapter.choose(connection("clef"), "cf-token", question(), Duration.ofSeconds(5));

        assertEquals("/client/v4/accounts/" + ACCOUNT + "/ai/run/@cf/cloudflare/clef", path.get());
        assertEquals("QUESTION", decision.label());
        assertEquals(0.8, decision.probabilities().get("QUESTION"));
    }

    @Test void aRefusedTokenIsAFailure() {
        answer(403, "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"Authentication error\"}]}");
        assertThrows(RuntimeException.class,
                () -> adapter.choose(connection("clef-flash"), "cf-token", question(), Duration.ofSeconds(5)));
    }
}
