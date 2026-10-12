package io.memoryos.integration.microsoft;

import static io.memoryos.connector.SharePointProviderException.Failure.AUTHENTICATION;
import static io.memoryos.connector.SharePointProviderException.Failure.AUTHORIZATION;
import static io.memoryos.connector.SharePointProviderException.Failure.LIMIT_EXCEEDED;
import static io.memoryos.connector.SharePointProviderException.Failure.MALFORMED;
import static io.memoryos.connector.SharePointProviderException.Failure.NOT_FOUND;
import static io.memoryos.connector.SharePointProviderException.Failure.QUOTA;
import static io.memoryos.connector.SharePointProviderException.Failure.RESYNC_REQUIRED;
import static io.memoryos.connector.SharePointProviderException.Failure.UNAVAILABLE;

import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.authentication.AccessTokenProvider;
import com.microsoft.kiota.authentication.AllowedHostsValidator;
import com.microsoft.kiota.authentication.BaseBearerTokenAuthenticationProvider;
import com.microsoft.kiota.http.middleware.options.RedirectHandlerOption;
import io.memoryos.connector.SharePointGateway;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.shared.RetryAfter;
import io.memoryos.integration.microsoft.GraphTransport.Exchange;
import io.memoryos.integration.microsoft.SharePointProviderMetrics.Operation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SharePoint through the Microsoft Graph SDK (MEM-226): the SDK builds the requests and reads the answers into its
 * typed models, on the transport of {@link GraphTransport}. What stays here is what MemoryOS decides: the budget of one
 * operation, which hosts a continuation or a download may go to, what a status means, and what a field may hold
 * ({@link GraphModels}).
 *
 * <p>What SharePoint lets somebody read is not in Graph: role assignments and the members of a SharePoint group are
 * read from SharePoint REST on the Tenant host (MEM-136), on the same transport and under the same rules.
 *
 * <p>Three addresses are still written out, with {@code withUrl}, because Graph addresses them by path or by a query
 * the SDK's builders do not offer, and their shape is the one proven against a Tenant: a site by its server-relative
 * path, a folder by its path, and a change log from a timestamp token.
 */
public final class RestSharePointGateway implements SharePointGateway, AutoCloseable {
    private static final String[] ITEM_FIELDS = {"id", "name", "size", "eTag", "file", "folder", "deleted",
            "createdDateTime", "lastModifiedDateTime", "parentReference", "webUrl", "sharepointIds"};
    private static final String[] PAGE_FIELDS = {"id", "name", "title", "description", "webUrl", "eTag",
            "lastModifiedDateTime"};
    private final SharePointProviderProperties properties;
    private final ObjectMapper mapper;
    private final GraphTransport transport;
    private final ExecutorService tokenExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final SharePointTokenSource tokens;
    private final SharePointProviderMetrics metrics;

    public RestSharePointGateway(SharePointProviderProperties properties, ObjectMapper mapper,
            MeterRegistry registry) {
        this(properties, mapper, null, registry);
    }

    RestSharePointGateway(SharePointProviderProperties properties, ObjectMapper mapper,
            @Nullable SharePointTokenSource tokenSource) {
        this(properties, mapper, tokenSource, new SimpleMeterRegistry());
    }

    RestSharePointGateway(SharePointProviderProperties properties, ObjectMapper mapper,
            @Nullable SharePointTokenSource tokenSource, MeterRegistry registry) {
        this.properties = properties;
        this.mapper = mapper;
        // Invalid SharePoint configuration fails open() rather than preventing unrelated FILE or Drive startup.
        Duration connect = properties.connectTimeout();
        if (connect.isNegative() || connect.isZero() || connect.compareTo(Duration.ofSeconds(30)) > 0) {
            connect = Duration.ofSeconds(3);
        }
        this.transport = new GraphTransport(connect, properties.userAgent());
        this.tokens = tokenSource == null ? new MsalSharePointTokenSource(properties, tokenExecutor) : tokenSource;
        this.metrics = new SharePointProviderMetrics(registry);
    }

    @Override public Session open(Credential credential) {
        properties.validate();
        if (credential.cloud() != Cloud.GLOBAL) throw new SharePointProviderException(MALFORMED);
        var application = tokens.open(credential);
        return new GraphSession(application, credential.authMethod() == AuthMethod.CERTIFICATE,
                metrics.record(Operation.TOKEN, () -> application.token(SharePointTokenSource.GRAPH_SCOPE)));
    }

    @Override public void close() {
        transport.close();
        tokenExecutor.close();
    }

    private final class GraphSession implements Session {
        private volatile @Nullable String bearer;
        private volatile SharePointTokenSource.@Nullable Tokens application;
        private final boolean certificate;
        /** The SharePoint REST token, asked for when the first REST call needs it, and the host it is for. */
        private @Nullable String restBearer;
        private @Nullable String restHost;
        private final GraphServiceClient graph;
        private final String base = properties.graphBaseUrl().toString();

        private GraphSession(SharePointTokenSource.Tokens application, boolean certificate, String bearer) {
            this.application = application;
            this.certificate = certificate;
            this.bearer = bearer;
            // The token goes to the configured Graph host only; the SDK asks this validator before it attaches one.
            var hosts = new AllowedHostsValidator(properties.graphBaseUrl().getHost());
            graph = new GraphServiceClient(new BaseBearerTokenAuthenticationProvider(new AccessTokenProvider() {
                @Override public String getAuthorizationToken(URI uri, @Nullable Map<String, Object> context) {
                    return token();
                }
                @Override public AllowedHostsValidator getAllowedHostsValidator() { return hosts; }
            }), transport.client());
            graph.getRequestAdapter().setBaseUrl(base);
        }

        private String token() {
            String token = bearer;
            if (token == null) throw new SharePointProviderException(MALFORMED);
            return token;
        }

        @Override public RootSite root() {
            return metrics.record(Operation.ROOT_SITE, () -> GraphModels.rootSite(json(x ->
                    graph.sites().bySiteId("root").get(request -> {
                        request.queryParameters.select = new String[] {"id", "webUrl", "siteCollection"};
                        request.options.add(x);
                    }))));
        }

        @Override public Site site(String hostname, String sitePath) {
            String url = base + "/sites/" + encodePath(hostname) + ":" + encodePath(sitePath)
                    + "?$select=id,webUrl,displayName,isPersonalSite";
            return metrics.record(Operation.SITE, () -> GraphModels.site(json(x ->
                    graph.sites().bySiteId("path").withUrl(url).get(request -> request.options.add(x)))));
        }

        @Override public List<Library> libraries(String siteId) {
            return metrics.record(Operation.LIBRARIES, () -> {
                var drives = json(x -> graph.sites().bySiteId(id(siteId)).drives().get(request -> {
                    request.queryParameters.select = new String[] {"id", "name", "webUrl", "driveType"};
                    request.options.add(x);
                }));
                var libraries = new ArrayList<Library>();
                for (var drive : GraphModels.values(drives.getValue())) {
                    var library = GraphModels.library(drive);
                    if (library != null) libraries.add(library);
                }
                return List.copyOf(libraries);
            });
        }

        @Override public Folder folder(String driveId, List<String> folderSegments) {
            if (folderSegments.isEmpty()) throw new SharePointProviderException(MALFORMED);
            String path = String.join("/", folderSegments.stream().map(RestSharePointGateway::encodePath).toList());
            String url = base + "/drives/" + encodePath(driveId) + "/root:/" + path + "?$select=id,name,folder";
            return metrics.record(Operation.FOLDER, () -> GraphModels.folder(json(x ->
                    graph.drives().byDriveId(id(driveId)).items().byDriveItemId("root").withUrl(url)
                            .get(request -> request.options.add(x)))));
        }

        @Override public SitePage sites(@Nullable String nextLink) {
            return metrics.record(Operation.SITES, () -> {
                var all = graph.sites().getAllSites();
                var page = nextLink == null
                        ? json(x -> all.get(request -> {
                            request.queryParameters.select = new String[] {"id", "name", "webUrl", "isPersonalSite"};
                            request.options.add(x);
                        }))
                        : json(x -> all.withUrl(continuation(nextLink)).get(request -> request.options.add(x)));
                var sites = new ArrayList<Site>();
                for (var site : GraphModels.values(page.getValue())) sites.add(GraphModels.site(site));
                // A next link here is handed back as Graph sent it; it is checked when it is used.
                String next = page.getOdataNextLink();
                return new SitePage(sites, next == null || next.isBlank() ? null : next);
            });
        }

        @Override public DeltaPage delta(String driveId, @Nullable String token, @Nullable String link) {
            return metrics.record(Operation.DELTA, () -> {
                var delta = graph.drives().byDriveId(id(driveId)).items().byDriveItemId("root").delta();
                var page = link != null
                        ? json(x -> delta.withUrl(continuation(link)).get(request -> request.options.add(x)))
                        : token != null
                                ? json(x -> delta.withUrl(base + "/drives/" + encodePath(driveId) + "/root/delta?$top="
                                        + properties.pageSize() + "&$select=" + encodeQuery(String.join(",", ITEM_FIELDS))
                                        + "&token=" + encodeQuery(instant(token))).get(request -> request.options.add(x)))
                                : json(x -> delta.get(request -> {
                                    request.queryParameters.top = properties.pageSize();
                                    request.queryParameters.select = ITEM_FIELDS;
                                    request.options.add(x);
                                }));
                var items = new ArrayList<DriveItem>();
                for (var item : GraphModels.values(page.getValue())) items.add(GraphModels.item(item, driveId));
                return new DeltaPage(items, GraphModels.link(page.getOdataNextLink()),
                        GraphModels.link(page.getOdataDeltaLink()));
            });
        }

        @Override public ItemPage children(String driveId, String itemId, @Nullable String link) {
            return metrics.record(Operation.CHILDREN, () -> {
                var children = graph.drives().byDriveId(id(driveId)).items().byDriveItemId(id(itemId)).children();
                var page = link != null
                        ? json(x -> children.withUrl(continuation(link)).get(request -> request.options.add(x)))
                        : json(x -> children.get(request -> {
                            request.queryParameters.top = properties.pageSize();
                            request.queryParameters.select = ITEM_FIELDS;
                            request.options.add(x);
                        }));
                var items = new ArrayList<DriveItem>();
                for (var item : GraphModels.values(page.getValue())) items.add(GraphModels.item(item, driveId));
                return new ItemPage(items, GraphModels.link(page.getOdataNextLink()));
            });
        }

        @Override public DriveItem item(String driveId, String itemId) {
            var fields = new ArrayList<>(List.of(ITEM_FIELDS));
            fields.add(GraphModels.DOWNLOAD_URL);
            return metrics.record(Operation.ITEM, () -> GraphModels.item(json(x ->
                    graph.drives().byDriveId(id(driveId)).items().byDriveItemId(id(itemId)).get(request -> {
                        request.queryParameters.select = fields.toArray(String[]::new);
                        request.options.add(x);
                    })), driveId));
        }

        @Override public Content content(DriveItem item, String tenantHost, int maxBytes) {
            return metrics.record(Operation.CONTENT, () -> content0(item, tenantHost, maxBytes));
        }

        private Content content0(DriveItem item, String tenantHost, int maxBytes) {
            if (!item.file() || item.driveId() == null) throw new SharePointProviderException(MALFORMED);
            int limit = Math.min(maxBytes, properties.maxContentBytes());
            // A download has its own, longer budget, and one request of it may take all that is left.
            var budget = new Budget(properties.contentTimeout());
            byte[] bytes = item.downloadUrl() != null
                    ? download(URI.create(item.downloadUrl()), tenantHost, budget, limit)
                    : redirectedDownload(item, tenantHost, budget, limit);
            if (bytes.length == 0) throw new SharePointProviderException(MALFORMED);
            return new Content(item.name() == null ? item.id() : item.name(),
                    item.mimeType() == null ? "application/octet-stream" : item.mimeType(), bytes);
        }

        /**
         * Without a download address Graph answers /content with one redirect to an address that carries its own
         * short-lived credential. The SDK follows it under an option of this request: one hop, only to the Tenant host,
         * and without the bearer token.
         */
        private byte[] redirectedDownload(DriveItem item, String tenantHost, Budget budget, int limit) {
            return call(budget, limit, budget.remaining(), x -> {
                var redirect = new RedirectHandlerOption(1, response -> onTenantHost(response, tenantHost, x),
                        (request, url, proxy) -> request.removeHeader("Authorization"));
                try (InputStream stream = graph.drives().byDriveId(id(Objects.requireNonNull(item.driveId()))).items()
                        .byDriveItemId(id(item.id())).content().get(request -> {
                            request.options.add(x);
                            request.options.add(redirect);
                        })) {
                    return stream == null ? new byte[0] : stream.readAllBytes();
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }

        /** The address in the item carries its own short-lived credential, so no bearer token is attached to it. */
        private byte[] download(URI uri, String tenantHost, Budget budget, int limit) {
            requireTenantHost(uri.getScheme(), uri.getHost(), tenantHost);
            return call(budget, limit, budget.remaining(), x -> {
                var request = new Request.Builder().url(uri.toString()).tag(Exchange.class, x).build();
                try (Response response = transport.client().newCall(request).execute()) {
                    var body = response.body();
                    return body == null ? new byte[0] : body.bytes();
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            });
        }

        @Override public SitePageList pages(String siteId, @Nullable String link) {
            return metrics.record(Operation.PAGES, () -> {
                var sitePages = graph.sites().bySiteId(id(siteId)).pages().graphSitePage();
                var page = link != null
                        ? json(x -> sitePages.withUrl(continuation(link)).get(request -> request.options.add(x)))
                        : json(x -> sitePages.get(request -> {
                            request.queryParameters.top = properties.pageSize();
                            request.queryParameters.select = PAGE_FIELDS;
                            request.options.add(x);
                        }));
                var pages = new ArrayList<SitePageMetadata>();
                for (var entry : GraphModels.values(page.getValue())) pages.add(GraphModels.pageMetadata(entry));
                return new SitePageList(pages, GraphModels.link(page.getOdataNextLink()));
            });
        }

        @Override public PageContent page(String siteId, String pageId) {
            return metrics.record(Operation.PAGE, () -> {
                var page = json(x -> graph.sites().bySiteId(id(siteId)).pages().byBaseSitePageId(id(pageId))
                        .graphSitePage().get(request -> {
                            request.queryParameters.expand = new String[] {"canvasLayout"};
                            request.options.add(x);
                        }));
                var metadata = GraphModels.pageMetadata(page);
                return new PageContent(metadata, GraphModels.snapshot(mapper, page, metadata));
            });
        }

        @Override public List<RoleAssignment> itemRoleAssignments(String tenantHost, String webUrl, String listId,
                String listItemId) {
            String list;
            long row;
            try {
                list = UUID.fromString(listId).toString();
                row = Long.parseLong(listItemId);
            } catch (IllegalArgumentException malformed) {
                throw new SharePointProviderException(MALFORMED);
            }
            return roleAssignments(tenantHost, webUrl, "/_api/web/lists(guid'" + list + "')/items(" + row + ")");
        }

        @Override public List<RoleAssignment> pageRoleAssignments(String tenantHost, String webUrl,
                String serverRelativeUrl) {
            if (!serverRelativeUrl.startsWith("/") || serverRelativeUrl.contains("?") || serverRelativeUrl.contains("#")) {
                throw new SharePointProviderException(MALFORMED);
            }
            // The address stays as Microsoft wrote it, percent-encoding included; a quote is doubled, as OData asks.
            return roleAssignments(tenantHost, webUrl, "/_api/web/GetFileByServerRelativeUrl('"
                    + serverRelativeUrl.replace("'", "''") + "')/ListItemAllFields");
        }

        @Override public List<RoleAssignment> webRoleAssignments(String tenantHost, String webUrl) {
            return roleAssignments(tenantHost, webUrl, "/_api/web");
        }

        private List<RoleAssignment> roleAssignments(String tenantHost, String webUrl, String securable) {
            return metrics.record(Operation.ROLE_ASSIGNMENTS, () -> {
                var assignments = new ArrayList<RoleAssignment>();
                for (JsonNode entry : rest(tenantHost, web(webUrl, tenantHost) + securable
                        + "/roleassignments?$expand=Member,RoleDefinitionBindings&$top=100")) {
                    assignments.add(SharePointRestModels.roleAssignment(entry));
                }
                return List.copyOf(assignments);
            });
        }

        @Override public List<Principal> siteGroupMembers(String tenantHost, String webUrl, long groupId) {
            return metrics.record(Operation.SITE_GROUP, () -> {
                var members = new ArrayList<Principal>();
                for (JsonNode entry : rest(tenantHost, web(webUrl, tenantHost) + "/_api/web/sitegroups/getbyid("
                        + groupId + ")/users")) {
                    members.add(SharePointRestModels.principal(entry));
                }
                return List.copyOf(members);
            });
        }

        @Override public EntraMemberPage entraGroupMembers(String groupId, @Nullable String link) {
            return metrics.record(Operation.ENTRA_GROUP, () -> {
                var members = graph.groups().byGroupId(id(groupId)).members();
                var page = link != null
                        ? json(x -> members.withUrl(continuation(link)).get(request -> request.options.add(x)))
                        : json(x -> members.get(request -> {
                            request.queryParameters.top = properties.pageSize();
                            request.queryParameters.select = new String[] {"id", "displayName", "userPrincipalName"};
                            request.options.add(x);
                        }));
                var listed = new ArrayList<EntraMember>();
                for (var entry : GraphModels.values(page.getValue())) {
                    var member = GraphModels.member(entry);
                    if (member != null) listed.add(member);
                }
                return new EntraMemberPage(listed, GraphModels.link(page.getOdataNextLink()));
            });
        }

        @Override public EntraGroupPage entraGroups(@Nullable String link) {
            return metrics.record(Operation.ENTRA_GROUP, () -> {
                var groups = graph.groups();
                var page = link != null
                        ? json(x -> groups.withUrl(continuation(link)).get(request -> request.options.add(x)))
                        : json(x -> groups.get(request -> {
                            request.queryParameters.top = 999;
                            request.queryParameters.select = new String[] {"id", "displayName"};
                            request.options.add(x);
                        }));
                var listed = new ArrayList<EntraGroup>();
                for (var entry : GraphModels.values(page.getValue())) listed.add(GraphModels.group(entry));
                return new EntraGroupPage(listed, GraphModels.link(page.getOdataNextLink()));
            });
        }

        @Override public List<String> sharingLinkScopes(String driveId, String itemId) {
            return metrics.record(Operation.SHARING_LINKS, () -> {
                var permissions = graph.drives().byDriveId(id(driveId)).items().byDriveItemId(id(itemId)).permissions();
                var budget = new Budget(properties.acquisitionTimeout());
                var scopes = new ArrayList<String>();
                String link = null;
                do {
                    String next = link;
                    var page = call(budget, properties.maxResponseBytes(),
                            Math.min(properties.requestTimeout().toNanos(), budget.remaining()), x -> next != null
                                    ? permissions.withUrl(continuation(next)).get(request -> request.options.add(x))
                                    : permissions.get(request -> request.options.add(x)));
                    for (var permission : GraphModels.values(page.getValue())) {
                        var sharing = permission.getLink();
                        if (sharing != null && sharing.getScope() != null) {
                            scopes.add(sharing.getScope().toLowerCase(Locale.ROOT));
                        }
                    }
                    link = GraphModels.link(page.getOdataNextLink());
                } while (link != null);
                return List.copyOf(scopes);
            });
        }

        @Override public void close() {
            bearer = null;
            application = null;
            restBearer = null;
        }

        /**
         * The token SharePoint REST takes. It is the application's own token for the Tenant host, which SharePoint
         * accepts only when the application proved itself with a certificate, so no other credential asks for one.
         */
        private synchronized String restToken(String tenantHost) {
            var tokens = application;
            if (tokens == null) throw new SharePointProviderException(MALFORMED);
            if (!certificate) throw new SharePointProviderException(AUTHORIZATION);
            String token = restBearer;
            if (token == null || !tenantHost.equalsIgnoreCase(restHost)) {
                token = metrics.record(Operation.TOKEN, () -> tokens.token("https://" + tenantHost + "/.default"));
                restBearer = token;
                restHost = tenantHost;
            }
            return token;
        }

        /**
         * Every entry of a SharePoint REST collection, following its continuation on the Tenant host, within the
         * budget of one operation. SharePoint REST runs on the same transport and under the same rules as Graph.
         */
        private List<JsonNode> rest(String tenantHost, String address) {
            String token = restToken(tenantHost);
            var budget = new Budget(properties.acquisitionTimeout());
            var entries = new ArrayList<JsonNode>();
            String url = address;
            while (url != null) {
                var uri = URI.create(url);
                requireTenantHost(uri.getScheme(), uri.getHost(), tenantHost);
                var request = new Request.Builder().url(url).header("Authorization", "Bearer " + token)
                        .header("Accept", "application/json;odata=nometadata");
                JsonNode page = call(budget, properties.maxResponseBytes(),
                        Math.min(properties.requestTimeout().toNanos(), budget.remaining()), x -> {
                            try (Response response = transport.client().newCall(request.tag(Exchange.class, x).build())
                                    .execute()) {
                                var body = response.body();
                                return body == null ? null : mapper.readTree(body.bytes());
                            } catch (IOException failure) {
                                throw new IllegalStateException(failure);
                            }
                        }, true);
                JsonNode values = page.path("value");
                if (!values.isArray()) throw new SharePointProviderException(MALFORMED);
                values.forEach(entries::add);
                url = GraphModels.link(page.path("odata.nextLink").asString(null));
            }
            return entries;
        }

        /** One JSON answer of a new operation, within the response bound. */
        private <T> T json(Function<Exchange, @Nullable T> request) {
            var budget = new Budget(properties.acquisitionTimeout());
            return call(budget, properties.maxResponseBytes(),
                    Math.min(properties.requestTimeout().toNanos(), budget.remaining()), request);
        }

        /**
         * One request of an operation: it counts against the budget, runs for at most {@code timeoutNanos}, and
         * whatever fails is reported as a provider failure without Microsoft's text.
         */
        private <T> T call(Budget budget, int limit, long timeoutNanos, Function<Exchange, @Nullable T> request) {
            return call(budget, limit, timeoutNanos, request, false);
        }

        private <T> T call(Budget budget, int limit, long timeoutNanos, Function<Exchange, @Nullable T> request,
                boolean rest) {
            token();
            budget.request();
            var exchange = new Exchange(limit, timeoutNanos);
            T value;
            try {
                value = request.apply(exchange);
            } catch (RuntimeException failure) {
                throw failure(failure, exchange, rest);
            }
            budget.check();
            if (value == null) throw new SharePointProviderException(MALFORMED);
            return value;
        }

        /** Graph continuation links are absolute; only links back to the configured Graph host are followed. */
        private String continuation(String nextLink) {
            URI uri = URI.create(nextLink);
            URI graphBase = properties.graphBaseUrl();
            if (!uri.isAbsolute() || !graphBase.getHost().equalsIgnoreCase(uri.getHost())
                    || graphBase.getPort() != uri.getPort() || !graphBase.getScheme().equalsIgnoreCase(uri.getScheme())) {
                throw new SharePointProviderException(MALFORMED);
            }
            return nextLink;
        }
    }

    /** Whether a redirect stays on the Tenant host; one that leaves it is refused and remembered on the exchange. */
    private static boolean onTenantHost(Response response, String tenantHost, Exchange exchange) {
        String location = response.header("Location");
        HttpUrl target = location == null ? null : response.request().url().resolve(location);
        if (target != null && tenantHost(target.scheme(), target.host(), tenantHost)) return true;
        exchange.foreignRedirect = target != null;
        return false;
    }

    /**
     * What a failed request means. Graph error bodies are never read; only the status classifies the failure, and a
     * throttled or unavailable answer carries the wait Microsoft asked for in {@code Retry-After}. A connection that
     * failed or a deadline that passed is the provider being unavailable; an answer that arrived and could not be
     * read is malformed.
     */
    private static SharePointProviderException failure(RuntimeException failure, Exchange exchange, boolean rest) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SharePointProviderException provider) return provider;
            if (cause instanceof GraphTransport.TooLarge) return new SharePointProviderException(LIMIT_EXCEEDED);
            if (cause instanceof GraphTransport.Status status) {
                if (exchange.foreignRedirect) return new SharePointProviderException(AUTHORIZATION);
                var retryAfter = status.code == 429 || status.code == 503
                        ? RetryAfter.parse(status.retryAfter, Clock.systemUTC()) : null;
                // Entra issued this session's tokens, so SharePoint REST refusing one says what the application
                // may do there, not that its credential stopped working.
                return new SharePointProviderException(rest && status.code == 401 ? AUTHORIZATION : failure(status.code),
                        SharePointProviderException.Reason.UNCLASSIFIED, retryAfter);
            }
            if (cause.getCause() == cause) break;
        }
        return new SharePointProviderException(exchange.interrupted ? UNAVAILABLE : MALFORMED);
    }

    private static SharePointProviderException.Failure failure(int status) {
        return switch (status) {
            case 401 -> AUTHENTICATION;
            case 403 -> AUTHORIZATION;
            case 404 -> NOT_FOUND;
            // A delta token Microsoft no longer accepts; the caller restarts that library.
            case 410 -> RESYNC_REQUIRED;
            case 429 -> QUOTA;
            default -> status >= 500 || status == 408 ? UNAVAILABLE : MALFORMED;
        };
    }

    /**
     * Only an address on the Tenant SharePoint host may be downloaded, and its short-lived credential is
     * never logged or reported.
     */
    private static void requireTenantHost(@Nullable String scheme, @Nullable String host, String tenantHost) {
        if (!tenantHost(scheme, host, tenantHost)) throw new SharePointProviderException(AUTHORIZATION);
    }

    private static boolean tenantHost(@Nullable String scheme, @Nullable String host, String tenantHost) {
        boolean loopback = host != null && Set.of("localhost", "127.0.0.1", "[::1]").contains(host);
        return host != null && host.equalsIgnoreCase(tenantHost) && ("https".equalsIgnoreCase(scheme) || loopback);
    }

    /** The address of a site for a REST call: on the Tenant host, without a query, and without a trailing slash. */
    private static String web(String webUrl, String tenantHost) {
        URI uri;
        try {
            uri = new URI(webUrl);
        } catch (URISyntaxException malformed) {
            throw new SharePointProviderException(MALFORMED);
        }
        requireTenantHost(uri.getScheme(), uri.getHost(), tenantHost);
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new SharePointProviderException(MALFORMED);
        }
        return webUrl.endsWith("/") ? webUrl.substring(0, webUrl.length() - 1) : webUrl;
    }

    private static String instant(String token) {
        try {
            return Instant.parse(token).toString();
        } catch (DateTimeException exception) {
            throw new SharePointProviderException(MALFORMED);
        }
    }

    private static String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** An identifier the SDK places in a path; it encodes it, and this refuses what no identifier holds. */
    private static String id(String value) {
        if (value.isBlank() || value.length() > 2048 || value.contains("?") || value.contains("#")) {
            throw new SharePointProviderException(MALFORMED);
        }
        return value;
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(id(value), StandardCharsets.UTF_8)
                .replace("+", "%20").replace("%2F", "/").replace("%3A", ":");
    }

    private final class Budget {
        private final long deadline;
        private int requests;

        Budget(Duration timeout) { deadline = System.nanoTime() + timeout.toNanos(); }

        void request() {
            check();
            if (++requests > properties.maxRequests()) throw new SharePointProviderException(LIMIT_EXCEEDED);
        }

        long remaining() { return Math.max(1, deadline - System.nanoTime()); }

        void check() { if (System.nanoTime() - deadline >= 0) throw new SharePointProviderException(LIMIT_EXCEEDED); }
    }
}
