package io.memoryos.ai.anthropic;

import com.anthropic.backends.AnthropicBackend;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientAsync;
import com.anthropic.client.AnthropicClientAsyncImpl;
import com.anthropic.core.ClientOptions;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.Timeout;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.services.async.MessageServiceAsync;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

/**
 * Owns the SDK clients of one provider connection; a streamed call owns only its transport exchange, so cancelling
 * one stream never touches another. The shape is {@code OpenAiCancellation}'s on the Anthropic SDK's types.
 */
final class AnthropicCancellation implements AutoCloseable {
    private final Transport transport;
    private final AnthropicClientAsync client;

    /**
     * Connect and every read or write gap are bounded, the whole call is not, so a long streamed answer that keeps
     * producing tokens is never cut.
     */
    static Timeout gap(Duration readTimeout) {
        return Timeout.builder().connect(readTimeout).read(readTimeout).write(readTimeout).request(Duration.ZERO).build();
    }

    AnthropicCancellation(String baseUrl, String credential, Duration readTimeout) {
        var timeout = gap(readTimeout);
        transport = new Transport(AnthropicBackend.builder().baseUrl(baseUrl).apiKey(credential).build(), timeout);
        try {
            client = new AnthropicClientAsyncImpl(ClientOptions.builder().httpClient(transport).maxRetries(0)
                    .timeout(timeout).build());
        } catch (RuntimeException | Error failure) {
            transport.close();
            throw failure;
        }
    }

    AnthropicClient sync() { return client.sync(); }

    AnthropicClientAsync async() { return client; }

    @Override public void close() { client.close(); }

    /**
     * One streamed call on a view of the client whose transport exchange closes with the subscription, before or after
     * the response headers. {@code events}, when present, sees every stream event as the SDK parses it.
     */
    <T> Flux<T> stream(@Nullable Consumer<RawMessageStreamEvent> events, Function<AnthropicClientAsync, Flux<T>> call) {
        return Flux.defer(() -> {
            var scope = new Scope(transport);
            try {
                // The SDK dispatches a streamed call on another thread, so the scope rides in the view's immutable
                // options, not in thread state. The view shares the connection pool.
                var view = client.withOptions(options -> options.httpClient(scope));
                return call.apply(events == null ? view : observed(view, events))
                        // Close the raw response first: a blocked read holds the SDK reader's lock. The read then ends
                        // the stream rather than failing it, and the request is cancelled only once nothing listens,
                        // so a stop never reaches Spring AI, which logs every stream failure, as one.
                        .doOnCancel(scope::stop)
                        .doFinally(ignored -> scope.close());
            } catch (RuntimeException | Error failure) {
                scope.close();
                throw failure;
            }
        });
    }

    /**
     * Spring AI 2.0 keeps streamed thinking text to itself until the response ends; it publishes it per delta from
     * 2.1. Until then the events are read where the SDK hands the stream over, with the SDK's own event types.
     */
    private static AnthropicClientAsync observed(AnthropicClientAsync client, Consumer<RawMessageStreamEvent> events) {
        return intercept(AnthropicClientAsync.class, client, "messages", messages ->
                intercept(MessageServiceAsync.class, (MessageServiceAsync) messages, "withRawResponse", raw ->
                        intercept(MessageServiceAsync.WithRawResponse.class, (MessageServiceAsync.WithRawResponse) raw,
                                "createStreaming", pending -> observed(pending, events))));
    }

    @SuppressWarnings("unchecked")
    private static Object observed(Object pending, Consumer<RawMessageStreamEvent> events) {
        return ((CompletableFuture<HttpResponseFor<StreamResponse<RawMessageStreamEvent>>>) pending)
                .thenApply(response -> new HttpResponseFor<StreamResponse<RawMessageStreamEvent>>() {
                    @Override public int statusCode() { return response.statusCode(); }
                    @Override public Headers headers() { return response.headers(); }
                    @Override public InputStream body() { return response.body(); }
                    @Override public void close() { response.close(); }
                    @Override public StreamResponse<RawMessageStreamEvent> parse() {
                        var stream = response.parse();
                        return new StreamResponse<>() {
                            @Override public Stream<RawMessageStreamEvent> stream() { return stream.stream().peek(events); }
                            @Override public void close() { stream.close(); }
                        };
                    }
                });
    }

    /** {@code target} behind its interface, with the result of the abstract method {@code name} replaced. */
    private static <T> T intercept(Class<T> type, T target, String name, UnaryOperator<Object> result) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, arguments) -> {
            // A default method calls the abstract one on the proxy, so the overloads share one interception.
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, arguments);
            Object value;
            try {
                value = method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
            return method.getName().equals(name) ? result.apply(value) : value;
        }));
    }

    private static final class Scope implements HttpClient {
        private final HttpClient transport;
        private final AtomicBoolean closed = new AtomicBoolean();
        /** Set on cancel, before the subscription upstream is cancelled: a response is closed, a request is left. */
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final AtomicReference<@Nullable HttpResponse> response = new AtomicReference<>();
        private volatile @Nullable CompletableFuture<HttpResponse> future;

        Scope(HttpClient transport) { this.transport = transport; }

        @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
            return executeAsync(request, options).join();
        }

        @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
            if (closed.get()) {
                closeBody(request);
                return CompletableFuture.failedFuture(new CancellationException());
            }
            // Track the transport future, whose cancellation invokes Call.cancel(), not the SDK's dependent futures.
            var pending = transport.executeAsync(request, options);
            future = pending;
            pending.whenComplete((received, _) -> {
                if (received != null) {
                    response.set(received);
                    if (stopping.get() || closed.get()) closeResponse();
                }
            });
            if (closed.get()) {
                pending.cancel(true);
                closeResponse();
            }
            return pending;
        }

        void stop() {
            stopping.set(true);
            closeResponse();
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

    private static void closeBody(HttpRequest request) {
        var body = request.body();
        if (body != null) body.close();
    }

    /** The SDK future ends at headers; the response keeps {@code Call.cancel()} until its body closes. */
    private static final class Transport implements HttpClient {
        private static final RequestBody EMPTY_BODY = RequestBody.create(new byte[0], null);
        private final AnthropicBackend backend;
        private final OkHttpClient client;

        Transport(AnthropicBackend backend, Timeout timeout) {
            this.backend = backend;
            client = new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
                    .followSslRedirects(false).connectTimeout(timeout.connect()).readTimeout(timeout.read())
                    .writeTimeout(timeout.write()).callTimeout(timeout.request()).build();
            client.dispatcher().setMaxRequestsPerHost(client.dispatcher().getMaxRequests());
        }

        @Override public HttpResponse execute(HttpRequest request, RequestOptions options) {
            var prepared = prepare(request);
            var call = newCall(prepared, options);
            try {
                return response(call, call.execute());
            } catch (IOException failure) {
                throw new AnthropicIoException("Request failed", failure);
            } finally {
                closeBody(prepared);
            }
        }

        @Override public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions options) {
            var prepared = prepare(request);
            var result = new CompletableFuture<HttpResponse>();
            var call = newCall(prepared, options);
            result.whenComplete((_, failure) -> {
                if (failure instanceof CancellationException) call.cancel();
                closeBody(prepared);
            });
            call.enqueue(new Callback() {
                @Override public void onResponse(Call receivedCall, Response received) {
                    var response = response(receivedCall, received);
                    // Cancellation can win while response headers are arriving.
                    if (!result.complete(response)) response.close();
                }
                @Override public void onFailure(Call receivedCall, IOException failure) {
                    result.completeExceptionally(new AnthropicIoException("Request failed", failure));
                }
            });
            return result;
        }

        /** The backend adds the API version and the key; the address is the connection's, never the request's. */
        private HttpRequest prepare(HttpRequest request) {
            return backend.authorizeRequest(backend.prepareRequest(request).toBuilder().baseUrl(backend.baseUrl()).build());
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
                var contentType = source.contentType();
                var type = contentType == null ? null : MediaType.get(contentType);
                body = new RequestBody() {
                    @Override public @Nullable MediaType contentType() { return type; }
                    @Override public long contentLength() { return source.contentLength(); }
                    @Override public boolean isOneShot() { return !source.repeatable(); }
                    @Override public void writeTo(BufferedSink sink) { source.writeTo(sink.outputStream()); }
                };
            } else if (switch (request.method()) { case POST, PUT, PATCH -> true; default -> false; }) {
                body = EMPTY_BODY;
            }
            var wire = new Request.Builder().url(url.build()).method(request.method().name(), body);
            for (String name : request.headers().names())
                for (String value : request.headers().values(name)) wire.addHeader(name, value);
            return effective.newCall(wire.build());
        }

        private HttpResponse response(Call call, Response received) {
            var builder = Headers.builder();
            for (int index = 0; index < received.headers().size(); index++)
                builder.put(received.headers().name(index), received.headers().value(index));
            var headers = builder.build();
            var closing = new AtomicBoolean();
            // A read that fails because this response was closed ends the stream: the close is a stop, not a failure.
            // okio reports a read that races the close as IllegalStateException ("Unbalanced enter/exit" from its
            // timeout), not as an IOException, so both end the stream once the response is closing.
            var body = new FilterInputStream(Objects.requireNonNull(received.body()).byteStream()) {
                @Override public int read() throws IOException {
                    try {
                        return super.read();
                    } catch (IOException | RuntimeException failure) {
                        if (closing.get()) return -1;
                        throw failure;
                    }
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    try {
                        return super.read(buffer, offset, length);
                    } catch (IOException | RuntimeException failure) {
                        if (closing.get()) return -1;
                        throw failure;
                    }
                }
            };
            return backend.prepareResponse(new HttpResponse() {
                @Override public int statusCode() { return received.code(); }
                @Override public Headers headers() { return headers; }
                @Override public InputStream body() { return body; }
                @Override public void close() {
                    closing.set(true);
                    // ResponseBody.close() may drain chunked SSE instead of stopping its producer.
                    call.cancel();
                    received.close();
                }
            });
        }

        @Override public void close() {
            backend.close();
            client.dispatcher().cancelAll();
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}
