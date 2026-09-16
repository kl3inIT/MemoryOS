package io.memoryos.provider.sharepoint;

import static io.memoryos.connector.SharePointProviderException.Failure.AUTHENTICATION;
import static io.memoryos.connector.SharePointProviderException.Failure.AUTHORIZATION;
import static io.memoryos.connector.SharePointProviderException.Failure.LIMIT_EXCEEDED;
import static io.memoryos.connector.SharePointProviderException.Failure.MALFORMED;
import static io.memoryos.connector.SharePointProviderException.Failure.NOT_FOUND;
import static io.memoryos.connector.SharePointProviderException.Failure.QUOTA;
import static io.memoryos.connector.SharePointProviderException.Failure.UNAVAILABLE;

import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class RestSharePointProvider implements SharePointProvider, AutoCloseable {
    private final SharePointProviderProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final ExecutorService tokenExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final SharePointTokenSource tokens;

    public RestSharePointProvider(SharePointProviderProperties properties, ObjectMapper mapper) {
        this(properties, mapper, null);
    }

    RestSharePointProvider(SharePointProviderProperties properties, ObjectMapper mapper,
            @Nullable SharePointTokenSource tokenSource) {
        this.properties = properties;
        this.mapper = mapper;
        // Invalid SharePoint configuration fails open() rather than preventing unrelated FILE or Drive startup.
        Duration connect = properties.connectTimeout();
        if (connect.isNegative() || connect.isZero() || connect.compareTo(Duration.ofSeconds(30)) > 0) {
            connect = Duration.ofSeconds(3);
        }
        this.client = HttpClient.newBuilder().connectTimeout(connect).followRedirects(HttpClient.Redirect.NEVER).build();
        this.tokens = tokenSource == null ? new MsalSharePointTokenSource(properties, tokenExecutor) : tokenSource;
    }

    @Override public Session open(Credential credential) {
        properties.validate();
        if (credential.cloud() != Cloud.GLOBAL) throw new SharePointProviderException(MALFORMED);
        return new GraphSession(tokens.token(credential));
    }

    @Override public void close() {
        client.close();
        tokenExecutor.close();
    }

    private final class GraphSession implements Session {
        private @Nullable String bearer;

        private GraphSession(String bearer) { this.bearer = bearer; }

        @Override public RootSite root() {
            JsonNode node = get("/sites/root?$select=id,webUrl,siteCollection", new Budget());
            return new RootSite(required(node, "id"), required(node, "webUrl"),
                    required(node.path("siteCollection"), "hostname"));
        }

        @Override public void close() { bearer = null; }

        private JsonNode get(String path, Budget budget) {
            String token = bearer;
            if (token == null) throw new SharePointProviderException(MALFORMED);
            URI base = properties.graphBaseUrl();
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("User-Agent", properties.userAgent())
                    .GET().build();
            return json(exchange(request, budget));
        }
    }

    private byte[] exchange(HttpRequest request, Budget budget) {
        budget.request();
        long timeout = Math.min(properties.requestTimeout().toNanos(), budget.remaining());
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info ->
                new LimitedBody(info.statusCode() >= 200 && info.statusCode() < 300 ? properties.maxResponseBytes() : 8_192));
        try {
            HttpResponse<byte[]> response = future.get(timeout, TimeUnit.NANOSECONDS);
            budget.check();
            int status = response.statusCode();
            if (status < 200 || status >= 300) throw httpFailure(status);
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new SharePointProviderException(UNAVAILABLE);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new SharePointProviderException(UNAVAILABLE);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof SharePointProviderException provider) throw provider;
            throw new SharePointProviderException(UNAVAILABLE);
        }
    }

    /** Graph error bodies are never echoed; only the status classifies the failure. */
    private static SharePointProviderException httpFailure(int status) {
        return new SharePointProviderException(switch (status) {
            case 401 -> AUTHENTICATION;
            case 403 -> AUTHORIZATION;
            case 404, 410 -> NOT_FOUND;
            case 429 -> QUOTA;
            default -> status >= 500 || status == 408 ? UNAVAILABLE : MALFORMED;
        });
    }

    private JsonNode json(byte[] bytes) {
        try {
            JsonNode node = mapper.readTree(bytes);
            if (node == null || !node.isObject()) throw new SharePointProviderException(MALFORMED);
            return node;
        } catch (tools.jackson.core.JacksonException exception) {
            throw new SharePointProviderException(MALFORMED);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asString("");
        if (value.isBlank()) throw new SharePointProviderException(MALFORMED);
        if (value.length() > 16_384) throw new SharePointProviderException(LIMIT_EXCEEDED);
        return value;
    }

    private final class Budget {
        private final long deadline = System.nanoTime() + properties.acquisitionTimeout().toNanos();
        private int requests;

        void request() {
            check();
            if (++requests > properties.maxRequests()) throw new SharePointProviderException(LIMIT_EXCEEDED);
        }

        long remaining() { return Math.max(1, deadline - System.nanoTime()); }

        void check() { if (System.nanoTime() - deadline >= 0) throw new SharePointProviderException(LIMIT_EXCEEDED); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;

        LimitedBody(int limit) { this.limit = limit; }

        @Override public CompletionStage<byte[]> getBody() { return result; }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) output.size() + buffer.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new SharePointProviderException(LIMIT_EXCEEDED));
                    return;
                }
                if (buffer.hasArray()) {
                    output.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
                    buffer.position(buffer.limit());
                } else {
                    while (buffer.hasRemaining()) output.write(buffer.get());
                }
            }
            subscription.request(1);
        }

        @Override public void onError(Throwable error) { result.completeExceptionally(error); }

        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
