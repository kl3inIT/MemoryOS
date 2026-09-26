package io.memoryos.ai.openai;

import io.memoryos.ai.ModelTurns;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.OpenAIClientAsyncImpl;
import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import com.openai.errors.OpenAIIoException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Owns the shared SDK client; subscription views own only their transport exchange. */
public final class OpenAiCancellation implements AutoCloseable {
    private final HttpClient transport;
    private final OpenAIClientAsync client;

    /**
     * As Onyx's socket read timeout: connect and every read/write gap are bounded, the whole call is not, so a long
     * streamed answer that keeps producing tokens is never cut. Helper calls still set their own total per request.
     */
    static Timeout gap(Duration readTimeout) {
        return Timeout.builder().connect(readTimeout).read(readTimeout).write(readTimeout).request(Duration.ZERO).build();
    }

    OpenAiCancellation(String baseUrl, String credential, Duration readTimeout) {
        var timeout = gap(readTimeout);
        transport = new Transport(timeout);
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

    /** Same cancellation scope, preserving the provider's per-turn view (hosted Web search, reasoning). */
    ChatModel decorateNative(Function<OpenAIClientAsync, ChatModel> modelFactory) {
        return new Native(modelFactory.apply(client), modelFactory, null);
    }

    private final class Native implements ChatModel, ModelTurns {
        private final ChatModel model;
        private final Function<OpenAIClientAsync, ChatModel> modelFactory;
        private final @Nullable Turn turn;

        Native(ChatModel model, Function<OpenAIClientAsync, ChatModel> modelFactory, @Nullable Turn turn) {
            this.model = model;
            this.modelFactory = modelFactory;
            this.turn = turn;
        }

        @Override public ChatModel forTurn(Turn value) { return new Native(model, modelFactory, value); }
        @Override public boolean nativeWebSearch() {
            return model instanceof ModelTurns turns && turns.nativeWebSearch();
        }
        @Override public ChatResponse call(Prompt prompt) { return model.call(prompt); }
        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> {
                var scope = new Scope(transport);
                try {
                    var view = client.withOptions(options -> options.httpClient(scope));
                    var rebuilt = modelFactory.apply(view);
                    var streaming = turn != null && rebuilt instanceof ModelTurns turnModel
                            ? turnModel.forTurn(turn) : rebuilt;
                    return streaming.stream(prompt)
                            .doOnCancel(scope::close)
                            .doFinally(ignored -> scope.close());
                } catch (RuntimeException | Error failure) {
                    scope.close();
                    throw failure;
                }
            });
        }
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
            // Track the transport future, whose cancellation invokes Call.cancel(),
            // not the dependent futures returned by the SDK service/retry layers.
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

    /** The SDK future ends at headers; the response must retain Call.cancel() until its body closes. */
    private static final class Transport implements HttpClient {
        private static final RequestBody EMPTY_BODY = RequestBody.create(new byte[0], null);
        private final OkHttpClient client;

        Transport(Timeout timeout) {
            client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                    .connectTimeout(timeout.connect()).readTimeout(timeout.read())
                    .writeTimeout(timeout.write()).callTimeout(timeout.request()).build();
            client.dispatcher().setMaxRequestsPerHost(client.dispatcher().getMaxRequests());
        }

        @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
            var call = newCall(request, options);
            try {
                return response(call, call.execute());
            } catch (IOException failure) {
                throw new OpenAIIoException("Request failed", failure);
            } finally {
                if (request.body() != null) request.body().close();
            }
        }

        @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
            var result = new CompletableFuture<HttpResponse>();
            var call = newCall(request, options);
            result.whenComplete((_, failure) -> {
                if (failure instanceof CancellationException) call.cancel();
                if (request.body() != null) request.body().close();
            });
            call.enqueue(new Callback() {
                @Override public void onResponse(Call receivedCall, Response received) {
                    var response = response(receivedCall, received);
                    // Cancellation can win while response headers are arriving.
                    if (!result.complete(response)) response.close();
                }
                @Override public void onFailure(Call receivedCall, IOException failure) {
                    result.completeExceptionally(new OpenAIIoException("Request failed", failure));
                }
            });
            return result;
        }

        private Call newCall(HttpRequest request, RequestOptions options) {
            var effective = client;
            var timeout = options.getTimeout();
            if (timeout != null) {
                effective = client.newBuilder().connectTimeout(timeout.connect()).readTimeout(timeout.read())
                        .writeTimeout(timeout.write()).callTimeout(timeout.request()).build();
            }
            var url = HttpUrl.get(request.baseUrl()).newBuilder();
            request.pathSegments().forEach(url::addPathSegment);
            for (String name : request.queryParams().keys())
                for (String value : request.queryParams().values(name)) url.addQueryParameter(name, value);
            RequestBody body = null;
            var source = request.body();
            if (source != null) {
                var type = source.contentType() == null ? null : MediaType.get(source.contentType());
                body = new RequestBody() {
                    @Override public @Nullable MediaType contentType() { return type; }
                    @Override public long contentLength() { return source.contentLength(); }
                    @Override public boolean isOneShot() { return !source.repeatable(); }
                    @Override public void writeTo(okio.BufferedSink sink) { source.writeTo(sink.outputStream()); }
                };
            } else if (switch (request.method()) { case POST, PUT, PATCH -> true; default -> false; }) {
                body = EMPTY_BODY;
            }
            var wire = new Request.Builder().url(url.build()).method(request.method().name(), body);
            for (String name : request.headers().names())
                for (String value : request.headers().values(name)) wire.addHeader(name, value);
            if (!request.headers().names().contains("X-Stainless-Read-Timeout") && effective.readTimeoutMillis() != 0)
                wire.addHeader("X-Stainless-Read-Timeout", Long.toString(Duration.ofMillis(effective.readTimeoutMillis()).toSeconds()));
            if (!request.headers().names().contains("X-Stainless-Timeout") && effective.callTimeoutMillis() != 0)
                wire.addHeader("X-Stainless-Timeout", Long.toString(Duration.ofMillis(effective.callTimeoutMillis()).toSeconds()));
            return effective.newCall(wire.build());
        }

        private static HttpResponse response(Call call, Response received) {
            var builder = Headers.builder();
            for (int index = 0; index < received.headers().size(); index++)
                builder.put(received.headers().name(index), received.headers().value(index));
            var headers = builder.build();
            var body = Objects.requireNonNull(received.body());
            return new HttpResponse() {
                @Override public int statusCode() { return received.code(); }
                @Override public Headers headers() { return headers; }
                @Override public InputStream body() { return body.byteStream(); }
                @Override public void close() {
                    // ResponseBody.close() may drain chunked SSE instead of stopping its producer.
                    call.cancel();
                    received.close();
                }
            };
        }

        @Override public void close() {
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}
