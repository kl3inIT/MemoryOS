package io.memoryos.connector.adapter.googledrive;

import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * How Google's API clients and OAuth requests run here (MEM-226). Left to itself {@code google-http-client} follows
 * redirects, retries, decodes and reads any response whole, and reads a failed body into its exception. A request
 * built with {@link #initializer} and run through {@link #exchange} keeps the transport rules of the provider calls
 * instead:
 *
 * <ul>
 *   <li>no redirect is followed and nothing is retried or backed off: the answer goes to the caller as it came;
 *   <li>a successful answer is refused when its declared length is over the bound, and read up to the bound and no
 *       further otherwise; it is never decompressed;
 *   <li>any other answer is read up to {@link #FAILED_BYTES}, because Google names the reason of a refusal there, and
 *       reported as a {@link Status}; the caller classifies it and never passes the text on;
 *   <li>the exchange ends at its deadline, counted from sending the request to the last byte read: the connection is
 *       closed and the caller released.
 * </ul>
 *
 * <p>The guard sits around the library's {@code HttpRequest} and not inside an {@code HttpTransport} of our own:
 * redirects and retries are decided in {@code HttpRequest.execute}, above any transport, and the library's
 * {@link NetHttpTransport} cannot be extended.
 */
final class GoogleTransport implements AutoCloseable {
    static final JsonFactory JSON = GsonFactory.getDefaultInstance();
    /** What is read of an answer that is not a success. */
    static final int FAILED_BYTES = 8_192;

    private final NetHttpTransport transport = new NetHttpTransport();
    private final ExecutorService exchanges = Executors.newVirtualThreadPerTaskExecutor();
    private final int connectMillis;
    private final int readMillis;

    /**
     * @param requestTimeout the longest a single exchange may be given; a read never waits longer than this, and
     *                       {@link #exchange} ends the exchange at its own, possibly shorter, deadline
     */
    GoogleTransport(Duration connectTimeout, Duration requestTimeout) {
        connectMillis = Math.toIntExact(connectTimeout.toMillis());
        readMillis = Math.toIntExact(Math.max(1, requestTimeout.toMillis()));
    }

    HttpTransport transport() { return transport; }

    /** The settings of every request, and the bearer token when the request is made for a session. */
    HttpRequestInitializer initializer(@Nullable String bearer) {
        return request -> {
            if (bearer != null) request.getHeaders().setAuthorization("Bearer " + bearer);
            request.getHeaders().setAcceptEncoding(null);
            request.setResponseReturnRawInputStream(true);
            request.setFollowRedirects(false);
            request.setNumberOfRetries(0);
            request.setUnsuccessfulResponseHandler(null);
            request.setIOExceptionHandler(null);
            request.setConnectTimeout(connectMillis);
            request.setReadTimeout(readMillis);
            request.setThrowExceptionOnExecuteError(false);
            // Runs before the library would parse a failed body into its own exception.
            request.setResponseInterceptor(response -> {
                if (response.isSuccessStatusCode()) return;
                try {
                    throw new Status(response.getStatusCode(), response.getHeaders().getRetryAfter(),
                            read(response, FAILED_BYTES, false));
                } finally {
                    response.disconnect();
                }
            });
        };
    }

    /** One request of a Google library, as that library sends it. */
    @FunctionalInterface
    interface Call {
        HttpResponse execute() throws IOException;
    }

    /**
     * Runs one request and returns the body of its successful answer.
     *
     * @throws Status      the answer was not a success
     * @throws TooLarge    the answer is longer than {@code limit}
     * @throws IOException the provider could not be reached, or the deadline passed
     */
    byte[] exchange(Call call, int limit, long timeoutNanos) throws IOException {
        Future<byte[]> exchange = exchanges.submit(() -> {
            HttpResponse response = call.execute();
            try {
                return read(response, limit, true);
            } finally {
                response.disconnect();
            }
        });
        try {
            return exchange.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException deadline) {
            // Interrupting a virtual thread blocked on a socket closes the socket, so nothing is left reading.
            exchange.cancel(true);
            throw new IOException("Google exchange passed its deadline");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            exchange.cancel(true);
            throw new IOException("Google exchange interrupted");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Google exchange failed");
        }
    }

    private static byte[] read(HttpResponse response, int limit, boolean refuse) throws IOException {
        Long declared = response.getHeaders().getContentLength();
        if (refuse && declared != null && declared > limit) throw new TooLarge();
        InputStream content = response.getContent();
        if (content == null) return new byte[0];
        byte[] bytes = content.readNBytes(refuse ? limit + 1 : limit);
        if (bytes.length > limit) throw new TooLarge();
        return bytes;
    }

    /** {@code scheme://authority/}: what a Google client is given as its root address. */
    static String root(URI base) {
        return base.getScheme() + "://" + base.getRawAuthority() + "/";
    }

    /** The path of a base address as a Google client's service path: no leading slash, one trailing slash. */
    static String servicePath(URI base) {
        String path = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("^/+|/+$", "");
        return path.isEmpty() ? "" : path + "/";
    }

    /**
     * The root address of a client whose request paths start with the API's version ({@code v4/spreadsheets/…}): the
     * configured base without that version, so a base of {@code https://sheets.googleapis.com/v4} and the client's own
     * path meet at the address Google documents. A base that does not end in the version is taken as the root.
     */
    static String rootBefore(URI base, String version) {
        String path = servicePath(base);
        if (path.equals(version + "/")) return root(base);
        if (path.endsWith("/" + version + "/")) return root(base) + path.substring(0, path.length() - version.length() - 1);
        return root(base) + path;
    }

    @Override public void close() {
        exchanges.shutdownNow();
        try {
            transport.shutdown();
        } catch (IOException ignored) {
            // Nothing is held open by a transport of HttpURLConnection.
        }
    }

    /** An answer that was not a success: its status, the wait Google asked for, and the start of its body. */
    static final class Status extends IOException {
        final int code;
        final @Nullable String retryAfter;
        final byte[] body;

        Status(int code, @Nullable String retryAfter, byte[] body) {
            super("Google status " + code);
            this.code = code;
            this.retryAfter = retryAfter;
            this.body = body;
        }
    }

    /** A successful answer longer than its bound. */
    static final class TooLarge extends IOException {
        TooLarge() { super("Google response over its bound"); }
    }
}
