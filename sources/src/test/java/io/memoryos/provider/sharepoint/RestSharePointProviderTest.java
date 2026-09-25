package io.memoryos.provider.sharepoint;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.connector.SharePointProvider;
import io.memoryos.connector.SharePointProviderException;
import io.memoryos.connector.SharePointProviderException.Failure;
import io.memoryos.connector.SharePointProviderException.Reason;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RestSharePointProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsTheRootSiteWithABearerTokenAndTheMicrosoftUserAgent() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertEquals("/v1.0/sites/root", exchange.getRequestURI().getPath());
            assertTrue(exchange.getRequestURI().getQuery().contains("siteCollection"));
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertTrue(exchange.getRequestHeaders().getFirst("User-Agent").startsWith("ISV|MemoryOS|"));
            return ok("""
                    {"id":"contoso.sharepoint.com,1,2","webUrl":"https://contoso.sharepoint.com",
                     "siteCollection":{"hostname":"contoso.sharepoint.com"}}""");
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var root = session.root();
            assertEquals("contoso.sharepoint.com,1,2", root.siteId());
            assertEquals("https://contoso.sharepoint.com", root.webUrl());
            assertEquals("contoso.sharepoint.com", root.hostname());
        }
    }

    @Test
    void classifiesGraphStatusCodesWithoutEchoingMicrosoftText() throws Exception {
        Map<Integer, Failure> expected = Map.of(401, Failure.AUTHENTICATION, 403, Failure.AUTHORIZATION,
                404, Failure.NOT_FOUND, 410, Failure.RESYNC_REQUIRED, 429, Failure.QUOTA,
                500, Failure.UNAVAILABLE, 503, Failure.UNAVAILABLE, 408, Failure.UNAVAILABLE, 400, Failure.MALFORMED);
        for (var entry : expected.entrySet()) {
            try (var fixture = new Fixture(_ -> new Response(entry.getKey(),
                    "{\"error\":{\"code\":\"secret\",\"message\":\"internal detail\"}}".getBytes(StandardCharsets.UTF_8)));
                    var provider = provider(fixture, 0); var session = provider.open(credential())) {
                var exception = assertThrows(SharePointProviderException.class, session::root);
                assertEquals(entry.getValue(), exception.failure(), "status " + entry.getKey());
                assertFalse(exception.getMessage().contains("internal detail"));
            }
        }
    }

    @Test
    void neverFollowsRedirects() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/elsewhere")) return ok("{\"id\":\"leaked\"}");
            exchange.getResponseHeaders().add("Location", "/v1.0/elsewhere");
            return new Response(302, new byte[0]);
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class, session::root).failure());
            assertEquals(1, fixture.requests.size());
        }
    }

    @Test
    void rejectsOversizedAndUnusableBodies() throws Exception {
        try (var fixture = new Fixture(_ -> ok("{\"id\":\"" + "x".repeat(4096) + "\"}"));
                var provider = provider(fixture, 1024); var session = provider.open(credential())) {
            assertEquals(Failure.LIMIT_EXCEEDED, assertThrows(SharePointProviderException.class, session::root).failure());
        }
        try (var fixture = new Fixture(_ -> ok("not json"));
                var provider = provider(fixture, 0); var session = provider.open(credential())) {
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class, session::root).failure());
        }
        try (var fixture = new Fixture(_ -> ok("{\"id\":\"site\",\"webUrl\":\"https://contoso.sharepoint.com\"}"));
                var provider = provider(fixture, 0); var session = provider.open(credential())) {
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class, session::root).failure(),
                    "a missing siteCollection.hostname is not a usable root site");
        }
    }

    @Test
    void refusesCloudsOtherThanTheConfiguredOneAndClosedSessions() throws Exception {
        try (var fixture = new Fixture(_ -> ok("{\"id\":\"site\"}")); var provider = provider(fixture, 0)) {
            var session = provider.open(credential());
            session.close();
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class, session::root).failure());
            assertEquals(0, fixture.requests.size());
        }
    }

    @Test
    void resolvesASiteByItsServerRelativePath() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertEquals("/v1.0/sites/contoso.sharepoint.com:/sites/Finance", exchange.getRequestURI().getPath());
            return ok("""
                    {"id":"contoso.sharepoint.com,1,2","webUrl":"https://contoso.sharepoint.com/sites/Finance",
                     "displayName":"Finance","isPersonalSite":false}""");
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var site = session.site("contoso.sharepoint.com", "/sites/Finance");
            assertEquals("contoso.sharepoint.com,1,2", site.siteId());
            assertEquals("Finance", site.displayName());
            assertFalse(site.personalSite());
        }
    }

    @Test
    void keepsTheLibraryUrlPathSoLocalizedNamesStillMatch() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertTrue(exchange.getRequestURI().getPath().endsWith("/drives"), exchange.getRequestURI().getPath());
            return ok("""
                    {"value":[
                      {"id":"cache","name":"PersonalCacheLibrary","driveType":"documentLibrary",
                       "webUrl":"https://contoso.sharepoint.com/sites/Finance/Lists/PersonalCacheLibrary"},
                      {"id":"drive-1","name":"Tài liệu","driveType":"documentLibrary",
                       "webUrl":"https://contoso.sharepoint.com/sites/Finance/Shared%20Documents"},
                      {"id":"other","name":"Ignored","driveType":"personal",
                       "webUrl":"https://contoso.sharepoint.com/sites/Finance/Other"}]}""");
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var libraries = session.libraries("contoso.sharepoint.com,1,2");
            assertEquals(2, libraries.size(), "only document libraries are returned");
            var library = libraries.get(1);
            assertEquals("drive-1", library.driveId());
            assertEquals("Tài liệu", library.name());
            // The decoded URL path is what a pasted address is matched against.
            assertEquals("/sites/Finance/Shared Documents", library.path());
        }
    }

    @Test
    void resolvesNestedFoldersAndRefusesFiles() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getRawPath().endsWith("/root:/Baocao/Quy%201")) {
                return ok("{\"id\":\"item-1\",\"name\":\"Quy 1\",\"folder\":{\"childCount\":2}}");
            }
            return ok("{\"id\":\"item-2\",\"name\":\"report.docx\",\"file\":{\"mimeType\":\"application/pdf\"}}");
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            assertEquals("item-1", session.folder("drive-1", List.of("Baocao", "Quy 1")).itemId());
            assertEquals(Failure.NOT_FOUND, assertThrows(SharePointProviderException.class,
                    () -> session.folder("drive-1", List.of("report.docx"))).failure());
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class,
                    () -> session.folder("drive-1", List.of())).failure());
        }
    }

    @Test
    void pagesAllSitesAndRefusesForeignContinuations() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getQuery() != null && exchange.getRequestURI().getQuery().contains("skiptoken")) {
                return ok("""
                        {"value":[{"id":"site-2","webUrl":"https://contoso.sharepoint.com/sites/People","name":"People",
                          "isPersonalSite":false}]}""");
            }
            String self = "http://127.0.0.1:" + exchange.getLocalAddress().getPort();
            return ok("""
                    {"value":[
                       {"id":"site-1","webUrl":"https://contoso.sharepoint.com/sites/Finance","name":"Finance","isPersonalSite":false},
                       {"id":"me","webUrl":"https://contoso-my.sharepoint.com/personal/ann","name":"Ann","isPersonalSite":true},
                       {"id":"search","webUrl":"https://contoso.sharepoint.com/search"}],
                     "@odata.nextLink":"%s/v1.0/sites/getAllSites?$skiptoken=next"}""".formatted(self));
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var first = session.sites(null);
            assertEquals(3, first.sites().size());
            assertTrue(first.sites().get(1).personalSite());
            // A site without a name must still be usable.
            assertNull(first.sites().get(2).displayName());
            assertNotNull(first.nextLink());
            assertEquals("site-2", session.sites(first.nextLink()).sites().getFirst().siteId());
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class,
                    () -> session.sites("https://evil.example.com/v1.0/sites/getAllSites")).failure());
        }
    }

    @Test
    void readsTheChangeLogIncludingTombstones() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertTrue(exchange.getRequestURI().getRawQuery().contains("token=2026-09-16T02%3A00%3A00Z"),
                    exchange.getRequestURI().getRawQuery());
            return ok("""
                    {"value":[
                      {"id":"file-1","name":"bao-cao.xlsx","size":16935,"eTag":"etag-1",
                       "createdDateTime":"2026-09-16T02:11:13Z","lastModifiedDateTime":"2026-09-16T02:11:13Z",
                       "file":{"mimeType":"application/vnd.ms-excel","hashes":{"quickXorHash":"S7GCo="}},
                       "parentReference":{"id":"root-1","driveId":"drive-1","path":"/drives/drive-1/root:/Baocao"},
                       "webUrl":"https://contoso.sharepoint.com/sites/Finance/Shared%20Documents/Baocao/bao-cao.xlsx"},
                      {"id":"folder-1","name":"Baocao","folder":{"childCount":1},
                       "parentReference":{"id":"root-1","driveId":"drive-1","path":"/drives/drive-1/root:"}},
                      {"id":"gone-1","deleted":{"state":"deleted"},"size":0,
                       "parentReference":{"id":"root-1","driveId":"drive-1"}}],
                     "@odata.deltaLink":"https://graph.invalid/delta?token=latest"}""");
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var page = session.delta("drive-1", "2026-09-16T02:00:00Z", null);
            assertEquals(3, page.items().size());
            var file = page.items().getFirst();
            assertTrue(file.file());
            assertEquals("/Baocao", file.parentPath());
            assertEquals("S7GCo=:16935", file.contentVersion(), "hash and size decide whether content changed");
            assertTrue(page.items().get(1).folder());
            var tombstone = page.items().get(2);
            assertTrue(tombstone.deleted());
            assertNull(tombstone.name(), "a tombstone has no name, only an identifier");
            assertEquals("gone-1", tombstone.id());
            assertNotNull(page.deltaLink());
            assertNull(page.nextLink());
        }
    }

    @Test
    void reportsAnExpiredChangeTokenAsResyncRequired() throws Exception {
        try (var fixture = new Fixture(_ -> new Response(410,
                "{\"error\":{\"code\":\"resyncRequired\",\"message\":\"Resync required.\"}}".getBytes(StandardCharsets.UTF_8)));
                var provider = provider(fixture, 0); var session = provider.open(credential())) {
            assertEquals(Failure.RESYNC_REQUIRED, assertThrows(SharePointProviderException.class,
                    () -> session.delta("drive-1", "2020-01-01T00:00:00Z", null)).failure());
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class,
                    () -> session.delta("drive-1", "not-a-time", null)).failure());
        }
    }

    @Test
    void walksFolderChildrenPageByPage() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getQuery().contains("skiptoken")) {
                return ok("{\"value\":[{\"id\":\"file-2\",\"name\":\"second.docx\",\"parentReference\":{\"driveId\":\"drive-1\"}}]}");
            }
            String self = "http://127.0.0.1:" + exchange.getLocalAddress().getPort();
            return ok(("""
                    {"value":[{"id":"file-1","name":"first.docx","parentReference":{"driveId":"drive-1"}}],
                     "@odata.nextLink":"%s/v1.0/drives/drive-1/items/folder-1/children?$skiptoken=next"}""")
                    .formatted(self));
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var first = session.children("drive-1", "folder-1", null);
            assertEquals("file-1", first.items().getFirst().id());
            assertNotNull(first.nextLink());
            assertEquals("file-2", session.children("drive-1", "folder-1", first.nextLink()).items().getFirst().id());
        }
    }

    @Test
    void downloadsOnlyFromTheTenantHost() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"),
                    "the download address carries its own credential");
            return new Response(200, "report-bytes".getBytes(StandardCharsets.UTF_8));
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var item = item("file-1", fixture.base + "/download?tempauth=secret");
            var content = session.content(item, "127.0.0.1", 1024);
            assertEquals("report-bytes", new String(content.bytes(), StandardCharsets.UTF_8));
            assertEquals("bao-cao.xlsx", content.filename());

            var foreign = item("file-1", "https://evil.example.com/download?tempauth=secret");
            var refused = assertThrows(SharePointProviderException.class, () -> session.content(foreign, "127.0.0.1", 1024));
            assertEquals(Failure.AUTHORIZATION, refused.failure());
            assertFalse(refused.getMessage().contains("tempauth"));
        }
    }

    @Test
    void followsExactlyOneContentRedirect() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/content")) {
                exchange.getResponseHeaders().add("Location",
                        "http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/download?tempauth=secret");
                return new Response(302, new byte[0]);
            }
            return new Response(200, "redirected-bytes".getBytes(StandardCharsets.UTF_8));
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var content = session.content(item("file-1", null), "127.0.0.1", 1024);
            assertEquals("redirected-bytes", new String(content.bytes(), StandardCharsets.UTF_8));
            assertEquals(2, fixture.requests.size(), "one redirect, then the download");
        }
    }

    @Test
    void stopsDownloadsThatExceedTheLimit() throws Exception {
        try (var fixture = new Fixture(_ -> new Response(200, new byte[4096]));
                var provider = provider(fixture, 0, 1024); var session = provider.open(credential())) {
            assertEquals(Failure.LIMIT_EXCEEDED, assertThrows(SharePointProviderException.class,
                    () -> session.content(item("file-1", fixture.base + "/download"), "127.0.0.1", 4096)).failure());
        }
    }

    private static SharePointProvider.DriveItem item(String id, String downloadUrl) {
        return new SharePointProvider.DriveItem(id, "bao-cao.xlsx", false, false, 12,
                "application/vnd.ms-excel", "S7GCo=", "\"{A},1\"", null, null, "root-1", "/Baocao",
                "https://contoso.sharepoint.com/sites/Finance/Shared%20Documents/bao-cao.xlsx", downloadUrl, "drive-1");
    }

    @Test
    void listsSitePagesAndSnapshotsWhatTheirCanvasSays() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getRawQuery() != null
                    && exchange.getRequestURI().getRawQuery().contains("expand=canvasLayout")) {
                return ok("""
                        {"id":"page-1","title":"Trang thử nghiệm","description":"Mô tả",
                         "webUrl":"https://contoso.sharepoint.com/sites/Finance/SitePages/Home.aspx",
                         "eTag":"etag-1","lastModifiedDateTime":"2026-09-16T02:30:00Z",
                         "titleArea":{"textAboveTitle":"Spike"},
                         "canvasLayout":{"horizontalSections":[{"columns":[{"webparts":[
                            {"@odata.type":"#microsoft.graph.textWebPart","innerHtml":"<p>Đoạn văn</p>"},
                            {"@odata.type":"#microsoft.graph.standardWebPart","webPartType":"c70391ea",
                             "data":{"title":"Liên kết nhanh","serverProcessedContent":{
                               "searchablePlainTexts":[{"key":"title","value":"Tiêu đề tìm được"}]}}}]}]}]}}""");
            }
            assertTrue(exchange.getRequestURI().getPath().endsWith("/pages/microsoft.graph.sitePage"),
                    exchange.getRequestURI().getPath());
            return ok("""
                    {"value":[{"id":"page-1","title":"Trang thử nghiệm","name":"Home.aspx",
                       "webUrl":"https://contoso.sharepoint.com/sites/Finance/SitePages/Home.aspx",
                       "eTag":"etag-1","lastModifiedDateTime":"2026-09-16T02:30:00Z"}]}""");
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var listed = session.pages("site-1", null);
            assertEquals(1, listed.pages().size());
            var metadata = listed.pages().getFirst();
            assertEquals("Trang thử nghiệm", metadata.title());
            assertEquals("etag-1:2026-09-16T02:30:00Z", metadata.contentVersion());
            assertNull(listed.nextLink());

            var page = session.page("site-1", "page-1");
            var snapshot = mapper.readTree(page.snapshot());
            assertEquals("memoryos-sharepoint-page-v1", snapshot.path("schema").asString(""));
            assertEquals("page-1", snapshot.path("source").path("id").asString(""));
            var content = snapshot.path("content");
            assertEquals("Trang thử nghiệm", content.path("title").asString(""));
            assertEquals("Mô tả", content.path("description").asString(""));
            assertEquals("Spike", content.path("textAboveTitle").asString(""));
            var parts = content.path("parts");
            assertEquals(2, parts.size());
            assertEquals("<p>Đoạn văn</p>", parts.path(0).path("html").asString(""));
            assertEquals("standard", parts.path(1).path("kind").asString(""));
            assertEquals("Tiêu đề tìm được", parts.path(1).path("texts").path(0).asString(""));
        }
    }

    @Test
    void aPageThatCannotBeReadFailsOnItsOwn() throws Exception {
        try (var fixture = new Fixture(_ -> new Response(400,
                "{\"error\":{\"code\":\"invalidRequest\"}}".getBytes(StandardCharsets.UTF_8)));
                var provider = provider(fixture, 0); var session = provider.open(credential())) {
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class,
                    () -> session.page("site-1", "page-1")).failure());
        }
    }

    @Test
    void classifiesEntraErrorNumbers() {
        assertEquals(Reason.INVALID_CLIENT_SECRET, MsalSharePointTokenSource.classify(
                "AADSTS7000215: Invalid client secret provided. Ensure the secret being sent in the request is the client secret value"));
        assertEquals(Reason.EXPIRED_CLIENT_SECRET, MsalSharePointTokenSource.classify("AADSTS7000222: The provided client secret keys are expired."));
        assertEquals(Reason.CERTIFICATE_NOT_REGISTERED, MsalSharePointTokenSource.classify(
                "AADSTS700027: The certificate with identifier used to sign the client assertion is not registered on application."));
        assertEquals(Reason.DIRECTORY_NOT_FOUND, MsalSharePointTokenSource.classify(
                "AADSTS900021: Requested tenant identifier is not valid."));
        assertEquals(Reason.APPLICATION_NOT_FOUND, MsalSharePointTokenSource.classify(
                "AADSTS700016: Application with identifier was not found in the directory."));
        assertEquals(Reason.CONSENT_REQUIRED, MsalSharePointTokenSource.classify("AADSTS65001: The user or administrator has not consented"));
        assertEquals(Reason.UNCLASSIFIED, MsalSharePointTokenSource.classify("AADSTS123456: Something else"));
        assertEquals(Reason.UNCLASSIFIED, MsalSharePointTokenSource.classify("connection reset"));
        assertEquals(Reason.UNCLASSIFIED, MsalSharePointTokenSource.classify(null));
    }

    private RestSharePointProvider provider(Fixture fixture, int maxResponseBytes) {
        return provider(fixture, maxResponseBytes, 0);
    }

    private RestSharePointProvider provider(Fixture fixture, int maxResponseBytes, int maxContentBytes) {
        var properties = new SharePointProviderProperties(fixture.base, URI.create(fixture.base + "/v1.0"),
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(10), 0, maxResponseBytes,
                0, maxContentBytes == 0 ? 0 : maxContentBytes, null);
        return new RestSharePointProvider(properties, mapper, _ -> "test-token");
    }

    private static SharePointProvider.Credential credential() {
        return SharePointProvider.Credential.clientSecret(SharePointProvider.Cloud.GLOBAL,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "secret".getBytes(StandardCharsets.UTF_8));
    }

    private static Response ok(String body) {
        return new Response(200, body.getBytes(StandardCharsets.UTF_8));
    }

    private record Response(int status, byte[] body) {}

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final URI base;
        final List<URI> requests = Collections.synchronizedList(new ArrayList<>());

        Fixture(Function<HttpExchange, Response> responder) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            server.createContext("/", exchange -> {
                requests.add(exchange.getRequestURI());
                Response response = responder.apply(exchange);
                exchange.sendResponseHeaders(response.status(), response.body().length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response.body());
                }
                exchange.close();
            });
            server.start();
        }

        @Override public void close() { server.stop(0); }
    }
}
