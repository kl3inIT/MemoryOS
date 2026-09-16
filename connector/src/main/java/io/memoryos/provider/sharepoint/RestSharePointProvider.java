package io.memoryos.provider.sharepoint;

import static io.memoryos.connector.SharePointProviderException.Failure.AUTHENTICATION;
import static io.memoryos.connector.SharePointProviderException.Failure.AUTHORIZATION;
import static io.memoryos.connector.SharePointProviderException.Failure.LIMIT_EXCEEDED;
import static io.memoryos.connector.SharePointProviderException.Failure.MALFORMED;
import static io.memoryos.connector.SharePointProviderException.Failure.NOT_FOUND;
import static io.memoryos.connector.SharePointProviderException.Failure.QUOTA;
import static io.memoryos.connector.SharePointProviderException.Failure.RESYNC_REQUIRED;
import static io.memoryos.connector.SharePointProviderException.Failure.UNAVAILABLE;

import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.provider.sharepoint.SharePointProviderMetrics.Operation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private static final String ITEM_FIELDS =
            "id,name,size,eTag,file,folder,deleted,createdDateTime,lastModifiedDateTime,parentReference,webUrl";
    private final SharePointProviderProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final ExecutorService tokenExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final SharePointTokenSource tokens;
    private final SharePointProviderMetrics metrics;

    public RestSharePointProvider(SharePointProviderProperties properties, ObjectMapper mapper,
            MeterRegistry registry) {
        this(properties, mapper, null, registry);
    }

    RestSharePointProvider(SharePointProviderProperties properties, ObjectMapper mapper,
            @Nullable SharePointTokenSource tokenSource) {
        this(properties, mapper, tokenSource, new SimpleMeterRegistry());
    }

    RestSharePointProvider(SharePointProviderProperties properties, ObjectMapper mapper,
            @Nullable SharePointTokenSource tokenSource, MeterRegistry registry) {
        this.properties = properties;
        this.mapper = mapper;
        // Invalid SharePoint configuration fails open() rather than preventing unrelated FILE or Drive startup.
        Duration connect = properties.connectTimeout();
        if (connect.isNegative() || connect.isZero() || connect.compareTo(Duration.ofSeconds(30)) > 0) {
            connect = Duration.ofSeconds(3);
        }
        this.client = HttpClient.newBuilder().connectTimeout(connect).followRedirects(HttpClient.Redirect.NEVER).build();
        this.tokens = tokenSource == null ? new MsalSharePointTokenSource(properties, tokenExecutor) : tokenSource;
        this.metrics = new SharePointProviderMetrics(registry);
    }

    @Override public Session open(Credential credential) {
        properties.validate();
        if (credential.cloud() != Cloud.GLOBAL) throw new SharePointProviderException(MALFORMED);
        return new GraphSession(metrics.record(Operation.TOKEN, () -> tokens.token(credential)));
    }

    @Override public void close() {
        client.close();
        tokenExecutor.close();
    }

    private final class GraphSession implements Session {
        private @Nullable String bearer;

        private GraphSession(String bearer) { this.bearer = bearer; }

        @Override public RootSite root() {
            return metrics.record(Operation.ROOT_SITE, () -> {
                JsonNode node = get("/sites/root?$select=id,webUrl,siteCollection", new Budget());
                return new RootSite(required(node, "id"), required(node, "webUrl"),
                        required(node.path("siteCollection"), "hostname"));
            });
        }

        @Override public Site site(String hostname, String sitePath) {
            return metrics.record(Operation.SITE, () -> site(get("/sites/" + encodePath(hostname) + ":"
                    + encodePath(sitePath) + "?$select=id,webUrl,displayName,isPersonalSite", new Budget())));
        }

        @Override public List<Library> libraries(String siteId) {
            return metrics.record(Operation.LIBRARIES, () -> libraries0(siteId));
        }

        private List<Library> libraries0(String siteId) {
            var node = get("/sites/" + encodePath(siteId) + "/drives?$select=id,name,webUrl,driveType", new Budget());
            var libraries = new ArrayList<Library>();
            for (JsonNode drive : array(node)) {
                if (!"documentLibrary".equals(drive.path("driveType").asString(""))
                        && !"business".equals(drive.path("driveType").asString(""))) continue;
                libraries.add(new Library(required(drive, "id"), required(drive, "name"),
                        libraryPath(required(drive, "webUrl"))));
            }
            return List.copyOf(libraries);
        }

        @Override public Folder folder(String driveId, List<String> folderSegments) {
            return metrics.record(Operation.FOLDER, () -> folder0(driveId, folderSegments));
        }

        private Folder folder0(String driveId, List<String> folderSegments) {
            if (folderSegments.isEmpty()) throw new SharePointProviderException(MALFORMED);
            String path = String.join("/", folderSegments.stream().map(RestSharePointProvider::encodePath).toList());
            var node = get("/drives/" + encodePath(driveId) + "/root:/" + path + "?$select=id,name,folder", new Budget());
            if (node.path("folder").isMissingNode()) throw new SharePointProviderException(NOT_FOUND);
            return new Folder(required(node, "id"), required(node, "name"));
        }

        @Override public SitePage sites(@Nullable String nextLink) {
            return metrics.record(Operation.SITES, () -> sites0(nextLink));
        }

        private SitePage sites0(@Nullable String nextLink) {
            var node = nextLink == null
                    ? get("/sites/getAllSites?$select=id,name,webUrl,isPersonalSite", new Budget())
                    : json(exchange(request(continuation(nextLink)), new Budget()));
            var sites = new ArrayList<Site>();
            for (JsonNode entry : array(node)) sites.add(site(entry));
            String next = node.path("@odata.nextLink").asString("");
            return new SitePage(sites, next.isBlank() ? null : next);
        }

        private Site site(JsonNode node) {
            String name = node.path("name").asString("");
            if (name.isBlank()) name = node.path("displayName").asString("");
            return new Site(required(node, "id"), required(node, "webUrl"), name.isBlank() ? null : name,
                    node.path("isPersonalSite").asBoolean(false));
        }

        @Override public DeltaPage delta(String driveId, @Nullable String token, @Nullable String link) {
            return metrics.record(Operation.DELTA, () -> delta0(driveId, token, link));
        }

        private DeltaPage delta0(String driveId, @Nullable String token, @Nullable String link) {
            JsonNode node = link != null
                    ? json(exchange(request(continuation(link)), new Budget()))
                    : get("/drives/" + encodePath(driveId) + "/root/delta?$top=" + properties.pageSize()
                            + "&$select=" + encodeQuery(ITEM_FIELDS)
                            + (token == null ? "" : "&token=" + encodeQuery(instant(token))), new Budget());
            var items = new ArrayList<DriveItem>();
            for (JsonNode entry : array(node)) items.add(parseItem(entry, driveId));
            return new DeltaPage(items, optionalLink(node, "@odata.nextLink"), optionalLink(node, "@odata.deltaLink"));
        }

        @Override public ItemPage children(String driveId, String itemId, @Nullable String link) {
            return metrics.record(Operation.CHILDREN, () -> children0(driveId, itemId, link));
        }

        private ItemPage children0(String driveId, String itemId, @Nullable String link) {
            JsonNode node = link != null
                    ? json(exchange(request(continuation(link)), new Budget()))
                    : get("/drives/" + encodePath(driveId) + "/items/" + encodePath(itemId) + "/children?$top="
                            + properties.pageSize() + "&$select=" + encodeQuery(ITEM_FIELDS), new Budget());
            var items = new ArrayList<DriveItem>();
            for (JsonNode entry : array(node)) items.add(parseItem(entry, driveId));
            return new ItemPage(items, optionalLink(node, "@odata.nextLink"));
        }

        @Override public DriveItem item(String driveId, String itemId) {
            return metrics.record(Operation.ITEM, () -> parseItem(get("/drives/" + encodePath(driveId) + "/items/"
                    + encodePath(itemId) + "?$select=" + encodeQuery(ITEM_FIELDS + ",@microsoft.graph.downloadUrl"),
                    new Budget()), driveId));
        }

        @Override public Content content(DriveItem item, String tenantHost, int maxBytes) {
            return metrics.record(Operation.CONTENT, () -> content0(item, tenantHost, maxBytes));
        }

        private Content content0(DriveItem item, String tenantHost, int maxBytes) {
            if (!item.file() || item.driveId() == null) throw new SharePointProviderException(MALFORMED);
            int limit = Math.min(maxBytes, properties.maxContentBytes());
            var budget = new Budget();
            byte[] bytes = item.downloadUrl() != null
                    ? download(URI.create(item.downloadUrl()), tenantHost, budget, limit)
                    : redirectedDownload(item, tenantHost, budget, limit);
            if (bytes.length == 0) throw new SharePointProviderException(MALFORMED);
            return new Content(item.name() == null ? item.id() : item.name(),
                    item.mimeType() == null ? "application/octet-stream" : item.mimeType(), bytes);
        }

        /** Without a download address Graph answers /content with one redirect, which must stay on the Tenant host. */
        private byte[] redirectedDownload(DriveItem item, String tenantHost, Budget budget, int limit) {
            URI uri = URI.create(properties.graphBaseUrl() + "/drives/"
                    + encodePath(Objects.requireNonNull(item.driveId())) + "/items/" + encodePath(item.id()) + "/content");
            var response = send(request(uri), budget, 8_192);
            if (response.statusCode() < 300 || response.statusCode() >= 400) throw httpFailure(response.statusCode());
            String location = response.headers().firstValue("Location").orElse("");
            if (location.isBlank()) throw new SharePointProviderException(MALFORMED);
            return download(URI.create(location), tenantHost, budget, limit);
        }

        private byte[] download(URI uri, String tenantHost, Budget budget, int limit) {
            requireTenantHost(uri, tenantHost);
            // The address carries its own short-lived credential, so no bearer token is attached to it.
            var request = HttpRequest.newBuilder(uri).header("User-Agent", properties.userAgent()).GET().build();
            var response = send(request, budget, limit);
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw httpFailure(response.statusCode());
            return response.body();
        }

        @Override public void close() { bearer = null; }

        private JsonNode get(String path, Budget budget) {
            return json(exchange(request(URI.create(properties.graphBaseUrl() + path)), budget));
        }

        private HttpRequest request(URI uri) {
            String token = bearer;
            if (token == null) throw new SharePointProviderException(MALFORMED);
            return HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("User-Agent", properties.userAgent())
                    .GET().build();
        }

        /** Graph continuation links are absolute; only links back to the configured Graph host are followed. */
        private URI continuation(String nextLink) {
            URI uri = URI.create(nextLink);
            URI base = properties.graphBaseUrl();
            if (!uri.isAbsolute() || !base.getHost().equalsIgnoreCase(uri.getHost())
                    || base.getPort() != uri.getPort() || !base.getScheme().equalsIgnoreCase(uri.getScheme())) {
                throw new SharePointProviderException(MALFORMED);
            }
            return uri;
        }
    }

    private byte[] exchange(HttpRequest request, Budget budget) {
        var response = send(request, budget, properties.maxResponseBytes());
        int status = response.statusCode();
        if (status < 200 || status >= 300) throw httpFailure(status);
        return response.body();
    }

    private HttpResponse<byte[]> send(HttpRequest request, Budget budget, int limit) {
        budget.request();
        long timeout = Math.min(properties.requestTimeout().toNanos(), budget.remaining());
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, info ->
                new LimitedBody(info.statusCode() >= 200 && info.statusCode() < 300 ? limit : 8_192));
        try {
            HttpResponse<byte[]> response = future.get(timeout, TimeUnit.NANOSECONDS);
            budget.check();
            return response;
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
            case 404 -> NOT_FOUND;
            // A delta token Microsoft no longer accepts; the caller restarts that library.
            case 410 -> RESYNC_REQUIRED;
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

    /**
     * Only an address on the Tenant SharePoint host may be downloaded, and its short-lived credential is
     * never logged or reported.
     */
    private static void requireTenantHost(URI uri, String tenantHost) {
        String host = uri.getHost();
        boolean loopback = host != null && java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(host);
        if (host == null || !host.equalsIgnoreCase(tenantHost)
                || (!"https".equalsIgnoreCase(uri.getScheme()) && !loopback)) {
            throw new SharePointProviderException(AUTHORIZATION);
        }
    }

    private DriveItem parseItem(JsonNode node, String driveId) {
        try {
            JsonNode file = node.path("file");
            JsonNode parent = node.path("parentReference");
            String parentPath = parent.path("path").asString("");
            int root = parentPath.indexOf("root:");
            String parentDrive = optional(parent, "driveId");
            return new DriveItem(required(node, "id"), optional(node, "name"), !node.path("folder").isMissingNode(),
                    !node.path("deleted").isMissingNode(), node.path("size").asLong(0),
                    optional(file, "mimeType"), optional(file.path("hashes"), "quickXorHash"), optional(node, "eTag"),
                    instantOrNull(optional(node, "createdDateTime")), instantOrNull(optional(node, "lastModifiedDateTime")),
                    optional(parent, "id"), root < 0 ? null : decode(parentPath.substring(root + "root:".length())),
                    optional(node, "webUrl"), optional(node, "@microsoft.graph.downloadUrl"),
                    parentDrive == null ? driveId : parentDrive);
        } catch (java.time.DateTimeException exception) {
            throw new SharePointProviderException(MALFORMED);
        }
    }

    private static @Nullable Instant instantOrNull(@Nullable String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static String decode(String value) {
        return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String instant(String token) {
        try {
            return Instant.parse(token).toString();
        } catch (java.time.DateTimeException exception) {
            throw new SharePointProviderException(MALFORMED);
        }
    }

    private static @Nullable String optionalLink(JsonNode node, String field) {
        String value = node.path(field).asString("");
        if (value.length() > 8_192) throw new SharePointProviderException(LIMIT_EXCEEDED);
        return value.isBlank() ? null : value;
    }

    private static @Nullable String optional(JsonNode node, String field) {
        String value = node.path(field).asString("");
        if (value.length() > 16_384) throw new SharePointProviderException(LIMIT_EXCEEDED);
        return value.isBlank() ? null : value;
    }

    private static String encodeQuery(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private JsonNode array(JsonNode node) {
        JsonNode value = node.path("value");
        if (!value.isArray()) throw new SharePointProviderException(MALFORMED);
        return value;
    }

    /** Keeps the library's server-relative path, dropping scheme and host. */
    private static String libraryPath(String webUrl) {
        URI uri = URI.create(webUrl);
        String path = uri.getPath();
        if (path == null || path.isBlank()) throw new SharePointProviderException(MALFORMED);
        return java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String encodePath(String value) {
        if (value.isBlank() || value.length() > 2048 || value.contains("?") || value.contains("#")) {
            throw new SharePointProviderException(MALFORMED);
        }
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20").replace("%2F", "/").replace("%3A", ":");
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
