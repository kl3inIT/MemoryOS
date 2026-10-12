package io.memoryos.integration.microsoft;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.connector.SharePointGateway;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RestSharePointGatewayTest {
    private static final String LIST = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private final ObjectMapper mapper = new ObjectMapper();
    /** The scope of every token a session asked for, in order. */
    private final List<String> scopes = Collections.synchronizedList(new ArrayList<>());

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
    void throttledAndUnavailableResponsesCarryTheWaitMicrosoftAskedFor() throws Exception {
        Map<Integer, Duration> expected = Map.of(429, Duration.ofSeconds(120),
                503, Duration.ofSeconds(7));
        for (var entry : expected.entrySet()) {
            try (var fixture = new Fixture(exchange -> {
                exchange.getResponseHeaders().add("Retry-After", Long.toString(entry.getValue().toSeconds()));
                return new Response(entry.getKey(), new byte[0]);
            }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
                var exception = assertThrows(SharePointProviderException.class, session::root);
                assertEquals(entry.getValue(), exception.retryAfter(), "status " + entry.getKey());
            }
        }
        try (var fixture = new Fixture(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "30");
            return new Response(404, new byte[0]);
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            assertNull(assertThrows(SharePointProviderException.class, session::root).retryAfter(),
                    "only a throttled or unavailable response asks the caller to wait");
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
                assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
                exchange.getResponseHeaders().add("Location",
                        "http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/download?tempauth=secret");
                return new Response(302, new byte[0]);
            }
            if (exchange.getRequestHeaders().getFirst("Authorization") != null) return new Response(500, new byte[0]);
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

    @Test
    void aContentRedirectThatLeavesTheTenantHostOrRedirectsAgainIsNotFollowed() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            exchange.getResponseHeaders().add("Location", "https://evil.example.com/download?tempauth=secret");
            return new Response(302, new byte[0]);
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var refused = assertThrows(SharePointProviderException.class,
                    () -> session.content(item("file-1", null), "127.0.0.1", 1024));
            assertEquals(Failure.AUTHORIZATION, refused.failure());
            assertEquals(1, fixture.requests.size(), "the foreign address is never contacted");
        }
        try (var fixture = new Fixture(exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/again");
            return new Response(302, new byte[0]);
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class,
                    () -> session.content(item("file-1", null), "127.0.0.1", 1024)).failure());
            assertEquals(2, fixture.requests.size(), "one hop, and the second redirect is the answer");
        }
    }

    @Test
    void anAnswerWithoutADeclaredLengthIsStoppedOnceItPassesTheBound() throws Exception {
        try (var fixture = new Fixture(_ -> new Response(200,
                ("{\"id\":\"" + "x".repeat(8192) + "\"}").getBytes(StandardCharsets.UTF_8), true));
                var provider = provider(fixture, 1024); var session = provider.open(credential())) {
            assertEquals(Failure.LIMIT_EXCEEDED, assertThrows(SharePointProviderException.class, session::root).failure());
        }
        try (var fixture = new Fixture(_ -> new Response(200, new byte[8192], true));
                var provider = provider(fixture, 0, 1024); var session = provider.open(credential())) {
            assertEquals(Failure.LIMIT_EXCEEDED, assertThrows(SharePointProviderException.class,
                    () -> session.content(item("file-1", fixture.base + "/download"), "127.0.0.1", 4096)).failure());
        }
    }

    @Test
    void anItemCarriesTheDownloadAddressGraphAnnotatesItWith() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertEquals("/v1.0/drives/drive-1/items/file-1", exchange.getRequestURI().getPath());
            assertTrue(exchange.getRequestURI().getQuery().contains("@microsoft.graph.downloadUrl"),
                    exchange.getRequestURI().getQuery());
            return ok("""
                    {"id":"file-1","name":"bao-cao.xlsx","size":12,"eTag":"etag-1",
                     "file":{"mimeType":"application/vnd.ms-excel","hashes":{"quickXorHash":"S7GCo="}},
                     "lastModifiedDateTime":"2026-09-16T02:30:00Z",
                     "parentReference":{"id":"root-1","driveId":"drive-1","path":"/drives/drive-1/root:/B%C3%A1o%20c%C3%A1o"},
                     "@microsoft.graph.downloadUrl":"https://contoso.sharepoint.com/download?tempauth=short-lived"}""");
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var item = session.item("drive-1", "file-1");
            assertEquals("https://contoso.sharepoint.com/download?tempauth=short-lived", item.downloadUrl());
            assertNull(item.listItem(), "Graph did not say where SharePoint keeps the item");
            assertEquals("S7GCo=", item.quickXorHash());
            assertEquals("/Báo cáo", item.parentPath());
            assertTrue(item.file());
        }
    }

    @Test
    void anItemCarriesTheListRowSharePointKeepsItIn() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertTrue(exchange.getRequestURI().getQuery().contains("sharepointIds"), exchange.getRequestURI().getQuery());
            return ok("""
                    {"value":[{"id":"file-1","name":"bao-cao.xlsx","size":12,"file":{"mimeType":"application/pdf"},
                       "parentReference":{"driveId":"drive-1"},
                       "sharepointIds":{"listId":"%s","listItemId":"7",
                         "siteUrl":"https://contoso.sharepoint.com/sites/Finance"}},
                      {"id":"file-2","name":"khac.docx","parentReference":{"driveId":"drive-1"},
                       "sharepointIds":{"listId":"%s"}}]}""".formatted(LIST, LIST));
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var items = session.children("drive-1", "folder-1", null).items();
            var kept = items.getFirst().listItem();
            assertNotNull(kept);
            assertEquals(LIST, kept.listId());
            assertEquals("7", kept.listItemId());
            assertEquals("https://contoso.sharepoint.com/sites/Finance", kept.webUrl());
            assertNull(items.get(1).listItem(), "a list without its row cannot be asked about");
        }
    }

    @Test
    void readsTheRoleAssignmentsOfAListItemByItsListIdentifierWithTheTenantHostToken() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertEquals("Bearer rest-token", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("application/json;odata=nometadata", exchange.getRequestHeaders().getFirst("Accept"));
            assertTrue(exchange.getRequestHeaders().getFirst("User-Agent").startsWith("ISV|MemoryOS|"));
            String self = "http://127.0.0.1:" + exchange.getLocalAddress().getPort();
            if (exchange.getRequestURI().getRawQuery().contains("skiptoken")) {
                return ok("""
                        {"value":[{"Member":{"Id":9,"PrincipalType":8,"LoginName":"Finance Members","Title":"Finance Members"},
                           "RoleDefinitionBindings":[{"RoleTypeKind":3}]}]}""");
            }
            assertEquals("/sites/Finance/_api/web/lists(guid'" + LIST + "')/items(7)/roleassignments",
                    exchange.getRequestURI().getPath());
            assertEquals("$expand=Member,RoleDefinitionBindings&$top=100", exchange.getRequestURI().getRawQuery());
            return ok("""
                    {"value":[
                      {"Member":{"Id":11,"PrincipalType":1,"LoginName":"i:0#.f|membership|an@contoso.onmicrosoft.com",
                         "Title":"An","UserPrincipalName":"an@contoso.onmicrosoft.com"},
                       "RoleDefinitionBindings":[{"RoleTypeKind":2}]},
                      {"Member":{"Id":12,"PrincipalType":4,
                         "LoginName":"c:0t.c|tenant|5f4dcc3b-5aa7-45d6-8cf3-99de8a2b4c1e","Title":"Kế toán"},
                       "RoleDefinitionBindings":[{"RoleTypeKind":1},{"RoleTypeKind":9}]},
                      {"Member":{"Id":13,"PrincipalType":2,"LoginName":"list@contoso.com"},
                       "RoleDefinitionBindings":[{"RoleTypeKind":1},{"RoleTypeKind":2}]}],
                     "odata.nextLink":"%s/sites/Finance/_api/web/lists(guid'%s')/items(7)/roleassignments?$skiptoken=next"}"""
                    .formatted(self, LIST));
        }); var provider = provider(fixture, 0); var session = provider.open(certificate())) {
            var assignments = session.itemRoleAssignments("127.0.0.1", fixture.base + "/sites/Finance/",
                    LIST.toUpperCase(Locale.ROOT), "7");
            assertEquals(4, assignments.size(), "both pages are read");
            var user = assignments.getFirst();
            assertEquals(SharePointGateway.PrincipalType.USER, user.member().type());
            assertEquals("an@contoso.onmicrosoft.com", user.member().userPrincipalName());
            assertFalse(user.limitedAccessOnly());
            var group = assignments.get(1);
            assertEquals(SharePointGateway.PrincipalType.ENTRA_GROUP, group.member().type());
            assertEquals("c:0t.c|tenant|5f4dcc3b-5aa7-45d6-8cf3-99de8a2b4c1e", group.member().loginName());
            assertEquals("Kế toán", group.member().title());
            assertTrue(group.limitedAccessOnly(), "every role bound is Limited Access");
            assertEquals(SharePointGateway.PrincipalType.OTHER, assignments.get(2).member().type());
            assertFalse(assignments.get(2).limitedAccessOnly(), "one real role is enough");
            assertEquals(SharePointGateway.PrincipalType.SHAREPOINT_GROUP, assignments.get(3).member().type());
            assertEquals(9, assignments.get(3).member().id());
            assertEquals(List.of(SharePointTokenSource.GRAPH_SCOPE, "https://127.0.0.1/.default"), scopes,
                    "the REST token is asked for once, for the Tenant host");
        }
    }

    @Test
    void readsTheRoleAssignmentsOfAPageAndOfTheSiteItself() throws Exception {
        try (var fixture = new Fixture(_ -> ok("{\"value\":[]}"));
                var provider = provider(fixture, 0); var session = provider.open(certificate())) {
            String web = fixture.base + "/sites/Finance";
            assertTrue(session.pageRoleAssignments("127.0.0.1", web, "/sites/Finance/SitePages/B%C3%A1o%20c%C3%A1o's.aspx")
                    .isEmpty());
            // The address keeps Microsoft's own percent-encoding; only the quote is doubled.
            assertEquals("/sites/Finance/_api/web/GetFileByServerRelativeUrl('/sites/Finance/SitePages/"
                    + "B%C3%A1o%20c%C3%A1o''s.aspx')/ListItemAllFields/roleassignments",
                    fixture.requests.getFirst().getRawPath());
            assertTrue(session.webRoleAssignments("127.0.0.1", web).isEmpty());
            assertEquals("/sites/Finance/_api/web/roleassignments", fixture.requests.get(1).getRawPath());
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class,
                    () -> session.pageRoleAssignments("127.0.0.1", web, "SitePages/Home.aspx")).failure());
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class,
                    () -> session.itemRoleAssignments("127.0.0.1", web, "Documents", "7")).failure(),
                    "a list is named by its identifier, never by a title");
            assertEquals(2, fixture.requests.size());
        }
    }

    @Test
    void listsTheDirectMembersOfASharePointGroup() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertEquals("/sites/Finance/_api/web/sitegroups/getbyid(9)/users", exchange.getRequestURI().getPath());
            return ok("""
                    {"value":[
                      {"Id":11,"PrincipalType":1,"LoginName":"i:0#.f|membership|an@contoso.com","Title":"An",
                       "UserPrincipalName":"an@contoso.com","Email":"an@contoso.com"},
                      {"Id":12,"PrincipalType":4,"LoginName":"c:0t.c|tenant|5f4dcc3b-5aa7-45d6-8cf3-99de8a2b4c1e",
                       "Title":"Kế toán","UserPrincipalName":null}]}""");
        }); var provider = provider(fixture, 0); var session = provider.open(certificate())) {
            var members = session.siteGroupMembers("127.0.0.1", fixture.base + "/sites/Finance", 9);
            assertEquals(2, members.size());
            assertEquals("an@contoso.com", members.getFirst().userPrincipalName());
            assertEquals(SharePointGateway.PrincipalType.ENTRA_GROUP, members.get(1).type());
            assertNull(members.get(1).userPrincipalName());
        }
    }

    @Test
    void aClientSecretCredentialNeverAsksSharePointRest() throws Exception {
        try (var fixture = new Fixture(_ -> ok("{\"value\":[]}"));
                var provider = provider(fixture, 0); var session = provider.open(credential())) {
            assertEquals(Failure.AUTHORIZATION, assertThrows(SharePointProviderException.class,
                    () -> session.webRoleAssignments("127.0.0.1", fixture.base + "/sites/Finance")).failure());
            assertEquals(0, fixture.requests.size());
            assertEquals(List.of(SharePointTokenSource.GRAPH_SCOPE), scopes, "no REST token is asked for either");
        }
    }

    @Test
    void sharePointRestStaysOnTheTenantHost() throws Exception {
        try (var fixture = new Fixture(_ -> ok("""
                {"value":[],"odata.nextLink":"https://evil.example.com/_api/web/roleassignments?$skiptoken=next"}"""));
                var provider = provider(fixture, 0); var session = provider.open(certificate())) {
            assertEquals(Failure.AUTHORIZATION, assertThrows(SharePointProviderException.class,
                    () -> session.webRoleAssignments("127.0.0.1", "https://evil.example.com/sites/Finance")).failure());
            assertEquals(0, fixture.requests.size(), "a site on another host is never asked");
            assertEquals(Failure.AUTHORIZATION, assertThrows(SharePointProviderException.class,
                    () -> session.webRoleAssignments("127.0.0.1", fixture.base + "/sites/Finance")).failure());
            assertEquals(1, fixture.requests.size(), "a continuation that leaves the host is not followed");
            assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class,
                    () -> session.webRoleAssignments("127.0.0.1", fixture.base + "/sites/Finance?x=1")).failure());
        }
    }

    @Test
    void classifiesSharePointRestStatusCodes() throws Exception {
        // SharePoint answers 401 to an application token it does not take; the credential itself still works.
        Map<Integer, Failure> expected = Map.of(401, Failure.AUTHORIZATION, 403, Failure.AUTHORIZATION,
                404, Failure.NOT_FOUND, 429, Failure.QUOTA, 503, Failure.UNAVAILABLE, 400, Failure.MALFORMED);
        for (var entry : expected.entrySet()) {
            try (var fixture = new Fixture(exchange -> {
                exchange.getResponseHeaders().add("Retry-After", "45");
                return new Response(entry.getKey(),
                        "{\"odata.error\":{\"message\":{\"value\":\"internal detail\"}}}".getBytes(StandardCharsets.UTF_8));
            }); var provider = provider(fixture, 0); var session = provider.open(certificate())) {
                var exception = assertThrows(SharePointProviderException.class,
                        () -> session.itemRoleAssignments("127.0.0.1", fixture.base + "/sites/Finance", LIST, "7"));
                assertEquals(entry.getValue(), exception.failure(), "status " + entry.getKey());
                assertFalse(exception.getMessage().contains("internal detail"));
                assertEquals(entry.getKey() == 429 || entry.getKey() == 503 ? Duration.ofSeconds(45) : null,
                        exception.retryAfter(), "status " + entry.getKey());
                assertEquals(1, fixture.requests.size(), "nothing is retried");
            }
        }
    }

    @Test
    void rejectsSharePointRestAnswersItCannotUse() throws Exception {
        try (var fixture = new Fixture(_ -> ok("{\"value\":[{\"Member\":{\"Id\":1,\"LoginName\":\""
                + "x".repeat(4096) + "\"},\"RoleDefinitionBindings\":[]}]}"));
                var provider = provider(fixture, 1024); var session = provider.open(certificate())) {
            assertEquals(Failure.LIMIT_EXCEEDED, assertThrows(SharePointProviderException.class,
                    () -> session.webRoleAssignments("127.0.0.1", fixture.base.toString())).failure());
        }
        for (String body : List.of("not json", "{\"d\":{}}", "{\"value\":[{\"RoleDefinitionBindings\":[]}]}",
                "{\"value\":[{\"Member\":{\"Id\":1},\"RoleDefinitionBindings\":[]}]}")) {
            try (var fixture = new Fixture(_ -> ok(body));
                    var provider = provider(fixture, 0); var session = provider.open(certificate())) {
                assertEquals(Failure.MALFORMED, assertThrows(SharePointProviderException.class,
                        () -> session.webRoleAssignments("127.0.0.1", fixture.base.toString())).failure(), body);
            }
        }
    }

    @Test
    void listsThePeopleAndGroupsOfAnEntraGroupPageByPage() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertEquals("Bearer test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestURI().getPath().contains("gone")) return new Response(404, new byte[0]);
            assertEquals("/v1.0/groups/group-1/members", exchange.getRequestURI().getPath());
            if (exchange.getRequestURI().getQuery().contains("skiptoken")) {
                return ok("""
                        {"value":[{"@odata.type":"#microsoft.graph.group","id":"group-2","displayName":"Kế toán"},
                          {"@odata.type":"#microsoft.graph.device","id":"device-1"}]}""");
            }
            assertTrue(exchange.getRequestURI().getQuery().contains("userPrincipalName"));
            String self = "http://127.0.0.1:" + exchange.getLocalAddress().getPort();
            return ok("""
                    {"value":[{"@odata.type":"#microsoft.graph.user","id":"user-1","displayName":"An",
                       "userPrincipalName":"an@contoso.com"}],
                     "@odata.nextLink":"%s/v1.0/groups/group-1/members?$skiptoken=next"}""".formatted(self));
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var first = session.entraGroupMembers("group-1", null);
            assertEquals(1, first.members().size());
            assertFalse(first.members().getFirst().group());
            assertEquals("an@contoso.com", first.members().getFirst().userPrincipalName());
            var second = session.entraGroupMembers("group-1", first.nextLink());
            assertEquals(1, second.members().size(), "a device is not a reader");
            assertTrue(second.members().getFirst().group());
            assertEquals("group-2", second.members().getFirst().id());
            assertEquals("Kế toán", second.members().getFirst().displayName());
            assertNull(second.nextLink());
            assertEquals(Failure.NOT_FOUND, assertThrows(SharePointProviderException.class,
                    () -> session.entraGroupMembers("gone", null)).failure());
        }
    }

    @Test
    void listsTheGroupsOfTheDirectoryAndTheScopesOfSharingLinks() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/v1.0/groups")) {
                assertTrue(exchange.getRequestURI().getQuery().contains("top=999"), exchange.getRequestURI().getQuery());
                return ok("{\"value\":[{\"id\":\"group-1\",\"displayName\":\"Kế toán\"},{\"id\":\"group-2\"}]}");
            }
            assertEquals("/v1.0/drives/drive-1/items/file-1/permissions", exchange.getRequestURI().getPath());
            return ok("""
                    {"value":[{"id":"1","roles":["read"],"link":{"scope":"Organization","type":"view"}},
                      {"id":"2","roles":["write"],"grantedToV2":{"user":{"id":"user-1"}}},
                      {"id":"3","roles":["read"],"link":{"scope":"anonymous","type":"view"}}]}""");
        }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
            var groups = session.entraGroups(null);
            assertEquals(2, groups.groups().size());
            assertEquals("Kế toán", groups.groups().getFirst().displayName());
            assertNull(groups.groups().get(1).displayName());
            assertNull(groups.nextLink());
            assertEquals(List.of("organization", "anonymous"), session.sharingLinkScopes("drive-1", "file-1"));
        }
    }

    @Test
    void theSdkDoesNotRetryAThrottledOrFailedAnswer() throws Exception {
        for (int status : List.of(429, 503, 504)) {
            try (var fixture = new Fixture(exchange -> {
                exchange.getResponseHeaders().add("Retry-After", "1");
                return new Response(status, new byte[0]);
            }); var provider = provider(fixture, 0); var session = provider.open(credential())) {
                assertThrows(SharePointProviderException.class, session::root);
                assertEquals(1, fixture.requests.size(), "status " + status + ": the caller decides when to try again");
            }
        }
    }

    @Test
    void anAnswerThatStallsIsCutOffAtTheRequestTimeoutAndADownloadHasItsOwnLongerOne() throws Exception {
        try (var fixture = new Fixture(_ -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new Response(200, "late-bytes".getBytes(StandardCharsets.UTF_8));
        }); var provider = provider(fixture, 0, 0, Duration.ofMillis(300), Duration.ofSeconds(30));
                var session = provider.open(credential())) {
            long started = System.nanoTime();
            assertEquals(Failure.UNAVAILABLE, assertThrows(SharePointProviderException.class, session::root).failure());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1_400, "cut off before the answer");
            // A file is allowed the content timeout, not the request timeout of a metadata read.
            var content = session.content(item("file-1", fixture.base + "/download"), "127.0.0.1", 1024);
            assertEquals("late-bytes", new String(content.bytes(), StandardCharsets.UTF_8));
        }
    }

    private static SharePointGateway.DriveItem item(String id, String downloadUrl) {
        return new SharePointGateway.DriveItem(id, "bao-cao.xlsx", false, false, 12,
                "application/vnd.ms-excel", "S7GCo=", "\"{A},1\"", null, null, "root-1", "/Baocao",
                "https://contoso.sharepoint.com/sites/Finance/Shared%20Documents/bao-cao.xlsx", downloadUrl, "drive-1",
                null);
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
            // The SDK casts with the namespace alias, which Graph accepts as it does microsoft.graph.sitePage.
            assertTrue(exchange.getRequestURI().getPath().endsWith("/pages/graph.sitePage"),
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

    private RestSharePointGateway provider(Fixture fixture, int maxResponseBytes) {
        return provider(fixture, maxResponseBytes, 0);
    }

    private RestSharePointGateway provider(Fixture fixture, int maxResponseBytes, int maxContentBytes) {
        return provider(fixture, maxResponseBytes, maxContentBytes, Duration.ofSeconds(5), null);
    }

    private RestSharePointGateway provider(Fixture fixture, int maxResponseBytes, int maxContentBytes,
            Duration requestTimeout, Duration contentTimeout) {
        var properties = new SharePointProviderProperties(fixture.base, URI.create(fixture.base + "/v1.0"),
                Duration.ofSeconds(1), requestTimeout, Duration.ofSeconds(10), 0, maxResponseBytes,
                0, maxContentBytes == 0 ? 0 : maxContentBytes, null, contentTimeout);
        return new RestSharePointGateway(properties, mapper, _ -> scope -> {
            scopes.add(scope);
            return scope.equals(SharePointTokenSource.GRAPH_SCOPE) ? "test-token" : "rest-token";
        });
    }

    private static SharePointGateway.Credential certificate() {
        return SharePointGateway.Credential.certificate(SharePointGateway.Cloud.GLOBAL, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), new byte[] {1}, new byte[] {2});
    }

    private static SharePointGateway.Credential credential() {
        return SharePointGateway.Credential.clientSecret(SharePointGateway.Cloud.GLOBAL,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "secret".getBytes(StandardCharsets.UTF_8));
    }

    private static Response ok(String body) {
        return new Response(200, body.getBytes(StandardCharsets.UTF_8));
    }

    /** @param chunked sent without a declared length, as a body whose size the server does not announce */
    private record Response(int status, byte[] body, boolean chunked) {
        Response(int status, byte[] body) { this(status, body, false); }
    }

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
                // Graph names the type of what it sends, and the SDK reads a JSON answer by it; a file is not JSON.
                boolean json = response.body().length > 0 && (response.body()[0] == '{' || response.body()[0] == '[');
                if (response.status() == 200) exchange.getResponseHeaders().add("Content-Type",
                        json || new String(response.body(), StandardCharsets.UTF_8).equals("not json")
                                ? "application/json" : "application/octet-stream");
                exchange.sendResponseHeaders(response.status(), response.chunked() ? 0 : response.body().length);
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
