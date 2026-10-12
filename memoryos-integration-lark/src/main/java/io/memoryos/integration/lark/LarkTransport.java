package io.memoryos.integration.lark;

import com.lark.oapi.core.httpclient.IHttpTransport;
import com.lark.oapi.core.request.FormData;
import com.lark.oapi.core.request.RawRequest;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.utils.Jsons;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * How Lark's SDK sends its requests here (MEM-245). The SDK builds the request and reads the answer into its models;
 * left to its own transport it waits without a deadline and reads any answer whole. This transport keeps the rules of
 * the other provider calls:
 *
 * <ul>
 *   <li>no redirect is followed and nothing is retried: the answer goes to the SDK as it came;
 *   <li>the exchange ends at its deadline, counted from sending the request to the last byte read;
 *   <li>a successful answer is refused when its declared or counted length is over the bound;
 *   <li>a throttled (429) or failed (5xx) answer is closed unread and reported as a {@link Status}; any other refusal
 *       is read up to {@link #FAILED_BYTES} and handed to the SDK, because Lark names the reason in the JSON
 *       {@code code} of that body. The caller classifies the code and never passes the text on.
 * </ul>
 *
 * <p>The SDK's {@code RawRequest} carries no per-request options, so the bound and the deadline of the exchange in
 * progress travel in {@link #EXCHANGE}, bound by the gateway around each call. A request made outside one, which the
 * gateway never does, gets the smallest budget.
 */
final class LarkTransport implements IHttpTransport, AutoCloseable {
    /** What is read of an answer that is not a success. */
    static final int FAILED_BYTES = 8_192;
    static final ScopedValue<Exchange> EXCHANGE = ScopedValue.newInstance();
    private static final Exchange UNBOUND = new Exchange(Duration.ofSeconds(10), FAILED_BYTES);

    /** The budget of one exchange. */
    record Exchange(Duration timeout, int maxBytes) {
    }

    private final HttpClient client;
    private final ExecutorService exchanges = Executors.newVirtualThreadPerTaskExecutor();
    private final String userAgent;

    LarkTransport(Duration connectTimeout, String userAgent) {
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1).build();
        this.userAgent = userAgent;
    }

    @Override public RawResponse execute(RawRequest request) throws IOException {
        Exchange exchange = EXCHANGE.isBound() ? EXCHANGE.get() : UNBOUND;
        HttpRequest http = request(request, exchange);
        Future<RawResponse> running = exchanges.submit(() -> exchange(http, exchange.maxBytes()));
        try {
            return running.get(exchange.timeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException deadline) {
            // Interrupting a virtual thread blocked on the exchange closes it, so nothing is left reading.
            running.cancel(true);
            throw new IOException("Lark exchange passed its deadline");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.cancel(true);
            throw new IOException("Lark exchange interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Lark exchange failed");
        }
    }

    private HttpRequest request(RawRequest request, Exchange exchange) {
        Object body = request.getBody();
        // The connector only reads; an upload would need the file parts the SDK's own transport writes.
        if (body instanceof FormData) throw new IllegalArgumentException("Lark uploads are not sent");
        var builder = HttpRequest.newBuilder(URI.create(request.getReqUrl())).timeout(exchange.timeout());
        for (Map.Entry<String, List<String>> header : request.getHeaders().entrySet())
            for (String value : header.getValue())
                if (!restricted(header.getKey())) builder.header(header.getKey(), value);
        builder.setHeader("User-Agent", userAgent);
        if (body == null) {
            builder.method(request.getHttpMethod(), HttpRequest.BodyPublishers.noBody());
        } else {
            var json = (request.isSupportLong2String() ? Jsons.LONG_TO_STR : Jsons.DEFAULT).toJson(body);
            builder.setHeader("Content-Type", "application/json; charset=utf-8");
            builder.method(request.getHttpMethod(),
                    HttpRequest.BodyPublishers.ofByteArray(json.getBytes(StandardCharsets.UTF_8)));
        }
        return builder.build();
    }

    /** Headers the JDK client sets itself and refuses from a caller. */
    private static boolean restricted(String name) {
        return name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Host")
                || name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("User-Agent")
                || name.equalsIgnoreCase("Content-Type");
    }

    private RawResponse exchange(HttpRequest http, int limit) throws IOException {
        HttpResponse<InputStream> response;
        try {
            response = client.send(http, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Lark exchange interrupted");
        }
        try (InputStream content = response.body()) {
            int status = response.statusCode();
            if (status == 429 || status >= 500)
                throw new Status(status, response.headers().firstValue("Retry-After").orElse(null),
                        response.headers().firstValue("x-ogw-ratelimit-reset").orElse(null));
            boolean success = status >= 200 && status < 300;
            byte[] bytes;
            if (success) {
                if (response.headers().firstValueAsLong("Content-Length").orElse(-1) > limit) throw new TooLarge();
                bytes = content.readNBytes(limit + 1);
                if (bytes.length > limit) throw new TooLarge();
            } else {
                bytes = content.readNBytes(FAILED_BYTES);
            }
            var raw = new RawResponse();
            raw.setStatusCode(status);
            // The SDK reads headers by their canonical names; the JDK client lower-cases them.
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll(response.headers().map());
            raw.setHeaders(headers);
            raw.setBody(bytes);
            return raw;
        }
    }

    @Override public void close() {
        exchanges.shutdownNow();
        client.shutdownNow();
    }

    /** A throttled or failed answer: its status and the wait Lark asked for. */
    static final class Status extends IOException {
        final int code;
        final @Nullable String retryAfter;
        /** Seconds until the rate limit resets, as Lark's gateway reports it. */
        final @Nullable String rateLimitReset;

        Status(int code, @Nullable String retryAfter, @Nullable String rateLimitReset) {
            super("Lark status " + code);
            this.code = code;
            this.retryAfter = retryAfter;
            this.rateLimitReset = rateLimitReset;
        }
    }

    /** A successful answer longer than its bound. */
    static final class TooLarge extends IOException {
        TooLarge() { super("Lark response over its bound"); }
    }
}
