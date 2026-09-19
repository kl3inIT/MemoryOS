package io.memoryos.api.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.chat.ChatActivityEvent;
import io.memoryos.chat.ChatEvidence;
import io.memoryos.chat.ChatReasoningDelta;
import io.memoryos.chat.execution.ChatModelTurns;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

class ChatCompletionsReasoningTest {
    // OpenRouter (qwen/qwen3.8-27b, probed 2026-09-20) streams "reasoning"; DeepSeek, vLLM and Ollama "reasoning_content".
    private static final String STREAM = """
            data: {"id":"g1","object":"chat.completion.chunk","created":1,"model":"qwen","choices":[{"index":0,"delta":{"role":"assistant","content":"","reasoning":"Gọi số áo 200k là z."}}]}

            data: {"id":"g1","object":"chat.completion.chunk","created":1,"model":"qwen","choices":[{"index":0,"delta":{"content":"","reasoning_content":" Áo 150k là 2z."}}]}

            data: {"id":"g1","object":"chat.completion.chunk","created":1,"model":"qwen","choices":[{"index":0,"delta":{"content":"Đáp án."}}]}

            data: {"id":"g1","object":"chat.completion.chunk","created":1,"model":"qwen","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: [DONE]

            """;
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private ChatCompletionsReasoning model() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = STREAM.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        return new ChatCompletionsReasoning(OpenAiChatModel.builder()
                .openAiClient(OpenAIOkHttpClient.builder().baseUrl(base).apiKey("k").build())
                .openAiClientAsync(OpenAIOkHttpClientAsync.builder().baseUrl(base).apiKey("k").build())
                .options(OpenAiChatOptions.builder().model("qwen").build()).build());
    }

    private static String text(List<ChatResponse> responses) {
        var text = new StringBuilder();
        for (var response : responses) if (response.getResult() != null)
            text.append(Objects.requireNonNullElse(response.getResult().getOutput().getText(), ""));
        return text.toString();
    }

    @Test
    void streamedReasoningReachesTheTurnOnceWhileTheAnswerIsUnchanged() throws Exception {
        var events = new ArrayList<ChatActivityEvent>();
        var turn = model().forTurn(new ChatModelTurns.Turn(new ChatEvidence(), events::add, false, () -> {}));

        var responses = turn.stream(new Prompt("Mỗi loại áo bao nhiêu?")).collectList().block();

        assertEquals("Đáp án.", text(responses));
        // Spring AI accumulates the reasoning in each chunk's metadata; only the new part of each chunk is published.
        assertEquals(List.of("\n\n", "Gọi số áo 200k là z.", " Áo 150k là 2z."),
                events.stream().map(event -> ((ChatReasoningDelta) event).text()).toList());
    }

    @Test
    void withoutATurnTheModelOnlyStreamsTheAnswer() throws Exception {
        var model = model();
        assertFalse(model.nativeWebSearch());
        assertEquals("Đáp án.", text(model.stream(new Prompt("q")).collectList().block()));
    }
}
