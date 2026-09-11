package io.memoryos.api.chat;

import com.openai.client.OpenAIClientAsync;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.client.okhttp.OkHttpClient;
import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Owns the shared SDK client; subscription views own only their transport exchange. */
final class OpenAiCancellation implements AutoCloseable {
    private final HttpClient transport;
    private final OpenAIClientAsync client;

    OpenAiCancellation(String baseUrl, String credential, Duration timeout) {
        transport = OkHttpClient.builder().timeout(Timeout.builder().request(timeout).build()).build();
        try {
            client = new OpenAIClientAsyncImpl(ClientOptions.builder().httpClient(transport).baseUrl(baseUrl)
                    .apiKey(credential).maxRetries(0).timeout(timeout).build());
        } catch (RuntimeException | Error failure) {
            transport.close();
            throw failure;
        }
    }

    @Override public void close() { client.close(); }

    ChatModel decorate(Function<OpenAIClientAsync, ChatModel> modelFactory) {
        var model = modelFactory.apply(client);
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) { return model.call(prompt); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    var scope = new Scope(transport);
                    try {
                        // SDK createStreaming dispatches with thenComposeAsync. Capture the scope
                        // in immutable client options, not thread state. withOptions shares the SDK
                        // mapper, executor and configuration; Scope shares the actual connection pool.
                        var view = client.withOptions(options -> options.httpClient(scope));
                        return modelFactory.apply(view).stream(prompt)
                                // SDK StreamHandler closes its BufferedReader before HttpResponse.
                                // Close the raw response first to unblock a read holding that reader's lock.
                                .doOnCancel(scope::close)
                                .doFinally(ignored -> scope.close());
                    } catch (RuntimeException | Error failure) {
                        scope.close();
                        throw failure;
                    }
                });
            }
        };
    }

    private static final class Scope implements HttpClient {
        private final HttpClient transport;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<HttpResponse> response = new AtomicReference<>();
        private volatile CompletableFuture<HttpResponse> future;

        Scope(HttpClient transport) { this.transport = transport; }

        @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
            return executeAsync(request, options).join();
        }

        @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
            if (closed.get()) {
                if (request.body() != null) request.body().close();
                return CompletableFuture.failedFuture(new CancellationException());
            }
            // This is the SDK OkHttp transport's original future, whose cancellation invokes
            // Call.cancel(), not the dependent futures returned by the SDK service/retry layers.
            var pending = transport.executeAsync(request, options);
            future = pending;
            pending.whenComplete((received, error) -> {
                if (received != null) {
                    response.set(received);
                    if (closed.get()) closeResponse();
                }
            });
            if (closed.get()) {
                pending.cancel(true);
                closeResponse();
            }
            return pending;
        }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                var pending = future;
                if (pending != null) pending.cancel(true);
                closeResponse();
            }
        }

        private void closeResponse() {
            var received = response.getAndSet(null);
            if (received != null) received.close();
        }
    }
}
