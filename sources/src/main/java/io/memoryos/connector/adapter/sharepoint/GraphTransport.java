package io.memoryos.connector.adapter.sharepoint;

import com.microsoft.graph.core.requests.GraphClientFactory;
import com.microsoft.kiota.RequestOption;
import com.microsoft.kiota.http.middleware.ParametersNameDecodingHandler;
import com.microsoft.kiota.http.middleware.RedirectHandler;
import com.microsoft.kiota.http.middleware.options.RedirectHandlerOption;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Source;
import org.jspecify.annotations.Nullable;

/**
 * The HTTP client the Microsoft Graph SDK runs on here (MEM-226). The SDK's own client follows redirects, retries and
 * reads any response whole; this one keeps the transport rules of the provider calls instead:
 *
 * <ul>
 *   <li>no redirect is followed, except the one a request asks for with its own {@link RedirectHandlerOption};
 *   <li>nothing is retried: a throttled or failed answer goes to the caller, which decides when to try again;
 *   <li>a successful answer is read up to the bound of its {@link Exchange} and no further;
 *   <li>any other answer is closed unread and reported by its status and {@code Retry-After} ({@link Status});
 *   <li>the exchange is cancelled at its deadline, counted from sending the request to closing the response.
 * </ul>
 */
final class GraphTransport implements AutoCloseable {
    private final OkHttpClient client;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("sharepoint-deadline").factory());

    GraphTransport(Duration connectTimeout, String userAgent) {
        // Order matters: the guard sees the final answer, after the SDK's redirect handler made the one hop a request
        // may have asked for.
        client = GraphClientFactory.create(new Guard(userAgent),
                        new RedirectHandler(new RedirectHandlerOption(0, _ -> false)), new ParametersNameDecodingHandler())
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(connectTimeout)
                // The guard's deadline covers the exchange; these would only cut a single slow read short of it.
                .readTimeout(Duration.ZERO).writeTimeout(Duration.ZERO).callTimeout(Duration.ZERO).build();
    }

    OkHttpClient client() { return client; }

    @Override public void close() {
        deadlines.shutdownNow();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /**
     * What one request is allowed: how many bytes of a successful answer, and until when. It travels with the request
     * as an SDK request option, which the SDK attaches to the OkHttp request as a tag.
     */
    static final class Exchange implements RequestOption {
        private final int limit;
        private final long timeoutNanos;
        /** Set when a redirect was refused because it left the allowed host. */
        volatile boolean foreignRedirect;
        /** Set when no answer arrived, the deadline cancelled the exchange, or the connection failed mid-answer. */
        volatile boolean interrupted;

        Exchange(int limit, long timeoutNanos) {
            this.limit = limit;
            this.timeoutNanos = timeoutNanos;
        }

        @SuppressWarnings("unchecked")
        @Override public <T extends RequestOption> Class<T> getType() { return (Class<T>) Exchange.class; }
    }

    /** An answer that was not a success: its status, and the wait the provider asked for when it sent one. */
    static final class Status extends IOException {
        final int code;
        final @Nullable String retryAfter;

        Status(int code, @Nullable String retryAfter) {
            super("Graph status " + code);
            this.code = code;
            this.retryAfter = retryAfter;
        }
    }

    /** A successful answer longer than its bound. */
    static final class TooLarge extends IOException {
        TooLarge() { super("Graph response over its bound"); }
    }

    private final class Guard implements Interceptor {
        private final String userAgent;

        Guard(String userAgent) { this.userAgent = userAgent; }

        @Override public Response intercept(Chain chain) throws IOException {
            var exchange = chain.request().tag(Exchange.class);
            if (exchange == null) throw new IOException("A Graph request without its limits");
            var call = chain.call();
            var deadline = deadlines.schedule(() -> {
                exchange.interrupted = true;
                call.cancel();
            }, exchange.timeoutNanos, TimeUnit.NANOSECONDS);
            Response response;
            try {
                response = chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build());
            } catch (IOException | RuntimeException failure) {
                deadline.cancel(false);
                // No answer arrived: the provider could not be reached, or the deadline passed first.
                exchange.interrupted = true;
                throw failure;
            }
            if (!response.isSuccessful()) {
                String retryAfter = response.header("Retry-After");
                response.close();
                deadline.cancel(false);
                throw new Status(response.code(), retryAfter);
            }
            var body = response.body();
            if (body == null) {
                deadline.cancel(false);
                return response;
            }
            if (body.contentLength() > exchange.limit) {
                response.close();
                deadline.cancel(false);
                throw new TooLarge();
            }
            return response.newBuilder().body(new Bounded(body, exchange, () -> deadline.cancel(false))).build();
        }
    }

    /** A body that fails once more than its bound was read, and tells the deadline when it is closed. */
    private static final class Bounded extends ResponseBody {
        private final ResponseBody body;
        private final BufferedSource source;
        private final Runnable closed;

        Bounded(ResponseBody body, Exchange exchange, Runnable closed) {
            this.body = body;
            this.closed = closed;
            this.source = Okio.buffer(limited(body.source(), exchange));
        }

        private static Source limited(Source source, Exchange exchange) {
            return new ForwardingSource(source) {
                private long total;

                @Override public long read(Buffer sink, long byteCount) throws IOException {
                    long read;
                    try {
                        read = super.read(sink, byteCount);
                    } catch (IOException failure) {
                        // The connection, not the content: this is not an answer that could not be parsed.
                        exchange.interrupted = true;
                        throw failure;
                    }
                    if (read > 0 && (total += read) > exchange.limit) throw new TooLarge();
                    return read;
                }
            };
        }

        @Override public @Nullable MediaType contentType() { return body.contentType(); }
        // The declared length is not handed on: a reader must not trust it over the bound.
        @Override public long contentLength() { return -1; }
        @Override public BufferedSource source() { return source; }

        @Override public void close() {
            try {
                super.close();
            } finally {
                closed.run();
            }
        }
    }
}
