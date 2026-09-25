package io.memoryos.connector.adapter.googledrive;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.GoogleDriveServiceAccountKey;
import io.memoryos.connector.GoogleDriveProvider.Permission;
import io.memoryos.connector.GoogleDriveProvider.PermissionDetail;
import io.memoryos.connector.GoogleDriveProviderException.Failure;
import io.memoryos.connector.SourceInputFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RestGoogleDriveProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void refreshRotationAndScopedChildrenRemainPageOriented() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertEquals("/files", exchange.getRequestURI().getPath());
            String query = decodedQuery(exchange);
            if (!query.contains("'parent' in parents") || !query.contains("supportsAllDrives=true")
                    || !query.contains("includeItemsFromAllDrives=true")) return new Response(403, new byte[0]);
            return query.contains("pageToken=files-next") ? ok("{\"files\":[]}")
                    : ok("{\"nextPageToken\":\"files-next\",\"files\":[" + metadata("text/plain", "1") + "]}");
        }); var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            assertArrayEquals("rotated".getBytes(StandardCharsets.UTF_8), session.rotatedRefreshToken());
            var first = session.listFiles("parent", null);
            assertEquals("file1", first.files().getFirst().id());
            assertEquals("files-next", first.nextPageToken());
            assertNull(session.listFiles("parent", first.nextPageToken()).nextPageToken());
            int requests = fixture.requests.size();
            for (String parent : new String[]{null, "", " ", "root"}) {
                assertEquals(Failure.MALFORMED, assertThrows(GoogleDriveProviderException.class,
                        () -> session.listFiles(parent, null)).failure());
            }
            assertEquals(requests, fixture.requests.size());
        }
    }

    @Test
    void permissionsTraverseEveryPageAndPreserveSharingPrincipalsWithoutExpandingGroups() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            assertEquals("/files/shared-file/permissions", exchange.getRequestURI().getPath());
            assertEquals("GET", exchange.getRequestMethod());
            assertEquals("Bearer access", exchange.getRequestHeaders().getFirst("Authorization"));
            String query = decodedQuery(exchange);
            assertTrue(query.contains("supportsAllDrives=true"));
            assertTrue(query.contains("pageSize=100"));
            assertTrue(query.contains("fields=nextPageToken,permissions(id,type,role,emailAddress,domain,expirationTime,"
                    + "allowFileDiscovery,deleted,pendingOwner,permissionDetails(permissionType,role,inheritedFrom,inherited),"
                    + "view,inheritedPermissionsDisabled)"));
            assertFalse(query.contains("useDomainAdminAccess"));
            if (query.contains("pageToken=next /+")) return ok("""
                    {"permissions":[
                      {"id":"domain:example.test","type":"domain","role":"reader","domain":"example.test","allowFileDiscovery":true},
                      {"id":"anyoneWithLink","type":"anyone","role":"reader","allowFileDiscovery":false,
                       "view":"metadata","inheritedPermissionsDisabled":true}]}
                    """);
            return ok("""
                    {"nextPageToken":"next /+","permissions":[
                      {"id":"user:123","type":"user","role":"writer","emailAddress":"Owner@Example.test",
                       "expirationTime":"2026-08-01T00:00:00Z","deleted":false,"pendingOwner":true,
                       "permissionDetails":[{"permissionType":"file","role":"writer","inherited":false}]},
                      {"id":"group:456","type":"group","role":"organizer","emailAddress":"Team@Example.test","deleted":true,
                       "permissionDetails":[
                         {"permissionType":"member","role":"organizer","inheritedFrom":"shared-drive","inherited":true},
                         {"permissionType":"file","role":"commenter","inherited":false}]}]}
                    """);
        }); var provider = new RestGoogleDriveProvider(new GoogleDriveProviderProperties(fixture.base.resolve("/token"),
                fixture.base, fixture.base, fixture.base, fixture.base, null, null, null, 1_000, 0, 0, 0, 0, 0), mapper);
             var credential = credential(); var session = provider.open(credential)) {
            var permissions = session.permissions("shared-file");
            assertEquals(List.of(
                    new Permission("user:123", "user", "writer", "Owner@Example.test", null,
                            Instant.parse("2026-08-01T00:00:00Z"), null, false, true,
                            List.of(new PermissionDetail("file", "writer", null, false)), null, null),
                    new Permission("group:456", "group", "organizer", "Team@Example.test", null, null, null, true, null,
                            List.of(new PermissionDetail("member", "organizer", "shared-drive", true),
                                    new PermissionDetail("file", "commenter", null, false)), null, null),
                    new Permission("domain:example.test", "domain", "reader", null, "example.test", null, true, null,
                            null, List.of(), null, null),
                    new Permission("anyoneWithLink", "anyone", "reader", null, null, null, false, null, null,
                            List.of(), "metadata", true)), permissions);
            assertThrows(UnsupportedOperationException.class, permissions::clear);
            assertThrows(UnsupportedOperationException.class, permissions.getFirst().permissionDetails()::clear);
            assertEquals(3, fixture.requests.size());
            assertEquals(List.of("grant_type=refresh_token&client_id=client.apps.googleusercontent.com"
                    + "&client_secret=secret&refresh_token=refresh"), fixture.tokenForms);
            String rendered = permissions.toString() + permissions.get(1).permissionDetails();
            assertFalse(rendered.contains("Example.test"));
            assertFalse(rendered.contains("shared-drive"));
            assertFalse(rendered.contains("user:123"));
        }
    }

    @Test
    void permissionOnlyChangesReuseTheOpenGrantWithoutAcquiringContentOrCachingAcl() throws Exception {
        AtomicInteger observations = new AtomicInteger();
        try (var fixture = new Fixture(exchange -> {
            assertEquals("Bearer access", exchange.getRequestHeaders().getFirst("Authorization"));
            if (exchange.getRequestURI().getPath().equals("/files/file1")) return ok(metadata("text/plain", "1"));
            assertEquals("/files/file1/permissions", exchange.getRequestURI().getPath());
            return observations.incrementAndGet() == 1 ? ok("""
                    {"permissions":[{"id":"reader","type":"user","role":"reader","emailAddress":"reader@example.test"}]}
                    """) : ok("{\"permissions\":[]}");
        }); var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            var before = session.metadata("file1");
            assertEquals("reader", session.permissions("file1").getFirst().id());
            assertEquals(before, session.metadata("file1"));
            assertEquals(List.of(), session.permissions("file1"));
            assertEquals(1, fixture.tokenForms.size());
            assertEquals(5, fixture.requests.size());
        }
    }

    @Test
    void laterPermissionPageFailuresNeverReturnTheFirstPageAsACompleteSnapshot() throws Exception {
        int[] statuses = {401, 403, 404, 429, 503};
        Failure[] failures = {Failure.AUTHENTICATION, Failure.ACCESS_DENIED, Failure.NOT_FOUND, Failure.QUOTA, Failure.UNAVAILABLE};
        for (int index = 0; index < statuses.length; index++) {
            int status = statuses[index];
            try (var fixture = new Fixture(exchange -> decodedQuery(exchange).contains("pageToken=next")
                    ? new Response(status, bytes("{\"error\":{\"message\":\"reader@example.test access\"}}"))
                    : ok("{\"nextPageToken\":\"next\",\"permissions\":[{\"id\":\"reader\",\"type\":\"user\",\"role\":\"reader\"}]}"));
                 var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
                var error = assertThrows(GoogleDriveProviderException.class, () -> session.permissions("file1"));
                assertEquals(failures[index], error.failure());
                assertFalse(error.toString().contains("reader@example.test"));
                assertEquals(3, fixture.requests.size());
            }
        }
    }

    @Test
    void throttledResponsesCarryTheWaitGoogleAskedFor() throws Exception {
        String rateLimit = "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}";
        record Case(int status, String body, java.time.Duration delay, Failure failure) {}
        for (var value : List.of(new Case(429, "{}", java.time.Duration.ofSeconds(30), Failure.QUOTA),
                new Case(403, rateLimit, java.time.Duration.ofSeconds(45), Failure.QUOTA),
                new Case(503, "{}", java.time.Duration.ofSeconds(5), Failure.UNAVAILABLE))) {
            try (var fixture = new Fixture(exchange -> {
                exchange.getResponseHeaders().add("Retry-After", Long.toString(value.delay().toSeconds()));
                return new Response(value.status(), bytes(value.body()));
            }); var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
                var error = assertThrows(GoogleDriveProviderException.class, () -> session.permissions("file1"));
                assertEquals(value.failure(), error.failure(), "status " + value.status());
                assertEquals(value.delay(), error.retryAfter(), "status " + value.status());
            }
        }
    }

    @Test
    void forbiddenReasonsSeparateMissingScopeFromUnreadableSharingAndUnavailableFiles() throws Exception {
        String scope = "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}";
        String scopeDetail = "{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\",\"details\":["
                + "{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\",\"reason\":\"ACCESS_TOKEN_SCOPE_INSUFFICIENT\"}]}}";
        String sharing = "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"insufficientFilePermissions\"}]}}";
        String quota = "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}";
        record Case(String body, boolean permissions, Failure expected) {}
        for (var example : List.of(
                new Case(scope, true, Failure.SCOPE_INSUFFICIENT),
                new Case(scopeDetail, true, Failure.SCOPE_INSUFFICIENT),
                new Case(sharing, true, Failure.ACCESS_DENIED),
                new Case(quota, true, Failure.QUOTA),
                new Case(scope, false, Failure.SCOPE_INSUFFICIENT),
                new Case(sharing, false, Failure.NOT_FOUND))) {
            try (var fixture = new Fixture(exchange -> new Response(403, bytes(example.body())));
                 var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
                var error = assertThrows(GoogleDriveProviderException.class, () -> {
                    if (example.permissions()) session.permissions("file1");
                    else session.metadata("file1");
                });
                assertEquals(example.expected(), error.failure(), example.toString());
            }
        }
    }

    @Test
    void duplicatePermissionIdsAndCyclicPageTokensRejectAmbiguousSnapshots() throws Exception {
        String entry = "{\"id\":\"reader\",\"type\":\"user\",\"role\":\"reader\"}";
        List<List<String>> pages = List.of(
                List.of("{\"permissions\":[" + entry + "," + entry + "]}"),
                List.of("{\"nextPageToken\":\"next\",\"permissions\":[" + entry + "]}",
                        "{\"permissions\":[" + entry + "]}"),
                List.of("{\"nextPageToken\":\"a\",\"permissions\":[]}",
                        "{\"nextPageToken\":\"a\",\"permissions\":[]}"),
                List.of("{\"nextPageToken\":\"a\",\"permissions\":[]}",
                        "{\"nextPageToken\":\"b\",\"permissions\":[]}",
                        "{\"nextPageToken\":\"a\",\"permissions\":[]}"));
        for (var responses : pages) {
            AtomicInteger page = new AtomicInteger();
            try (var fixture = new Fixture(exchange -> ok(responses.get(page.getAndIncrement())));
                 var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
                assertEquals(Failure.INCONSISTENT, assertThrows(GoogleDriveProviderException.class,
                        () -> session.permissions("file1")).failure());
                assertEquals(responses.size(), page.get());
            }
        }
    }

    @Test
    void malformedPermissionPagesCannotMasqueradeAsEmptyOrCompletePermissions() throws Exception {
        List<String> malformed = List.of(
                "{}", "{\"permissions\":null}", "{\"permissions\":{}}", "{\"permissions\":[null]}",
                "{\"permissions\":[],\"nextPageToken\":12}", "{\"permissions\":[],\"nextPageToken\":\" \"}",
                "{\"permissions\":[]} {\"permissions\":[]}", "{\"permissions\":[],\"permissions\":[]}",
                "{\"permissions\":[{\"id\":12,\"type\":\"user\",\"role\":\"reader\"}]}",
                "{\"permissions\":[{\"id\":\"x\",\"type\":\"user\",\"role\":\" \"}]}",
                "{\"permissions\":[{\"id\":\"x\",\"type\":\"user\"}]}",
                "{\"permissions\":[{\"id\":\"x\",\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":12}]}",
                "{\"permissions\":[{\"id\":\"x\",\"type\":\"user\",\"role\":\"reader\",\"deleted\":\"false\"}]}",
                "{\"permissions\":[{\"id\":\"x\",\"type\":\"user\",\"role\":\"reader\",\"expirationTime\":\"invalid\"}]}",
                "{\"permissions\":[{\"id\":\"x\",\"type\":\"user\",\"role\":\"reader\",\"permissionDetails\":{}}]}",
                "{\"permissions\":[{\"id\":\"x\",\"type\":\"user\",\"role\":\"reader\",\"permissionDetails\":[true]}]}",
                "{\"permissions\":[{\"id\":\"x\",\"type\":\"user\",\"role\":\"reader\",\"permissionDetails\":[{\"inherited\":0}]}]}",
                "{\"permissions\":[{\"id\":\"x\",\"type\":\"user\",\"role\":\"reader\",\"view\":true}]}",
                "{\"permissions\":[{\"id\":\"x\",\"type\":\"user\",\"role\":\"reader\",\"inheritedPermissionsDisabled\":\"true\"}]}");
        for (String response : malformed) {
            try (var fixture = new Fixture(exchange -> decodedQuery(exchange).contains("pageToken=next")
                    ? ok(response) : ok("{\"nextPageToken\":\"next\",\"permissions\":[{\"id\":\"first\",\"type\":\"user\",\"role\":\"owner\"}]}"));
                 var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
                assertEquals(Failure.MALFORMED, assertThrows(GoogleDriveProviderException.class,
                        () -> session.permissions("file1")).failure());
            }
        }
    }

    @Test
    void permissionTraversalSharesRequestAndCumulativeByteBudgetsAcrossPages() throws Exception {
        AtomicInteger pages = new AtomicInteger();
        try (var fixture = new Fixture(exchange -> ok("{\"nextPageToken\":\"page-" + pages.incrementAndGet() + "\",\"permissions\":[]}"));
             var provider = provider(fixture, 0, 2); var credential = credential(); var session = provider.open(credential)) {
            assertEquals(Failure.LIMIT_EXCEEDED, assertThrows(GoogleDriveProviderException.class,
                    () -> session.permissions("file1")).failure());
            assertEquals(2, pages.get());
        }
        String first = "{\"nextPageToken\":\"next\",\"permissions\":[{\"id\":\"first\",\"type\":\"user\",\"role\":\"reader\"}]}";
        String second = "{\"permissions\":[{\"id\":\"second\",\"type\":\"user\",\"role\":\"reader\"}]}";
        try (var fixture = new Fixture(exchange -> ok(decodedQuery(exchange).contains("pageToken=next") ? second : first));
             var provider = new RestGoogleDriveProvider(new GoogleDriveProviderProperties(fixture.base.resolve("/token"),
                     fixture.base, fixture.base, fixture.base, fixture.base, null, null, null, 0, 0, 0, 0, 0, bytes(first).length), mapper);
             var credential = credential(); var session = provider.open(credential)) {
            assertEquals(Failure.LIMIT_EXCEEDED, assertThrows(GoogleDriveProviderException.class,
                    () -> session.permissions("file1")).failure());
            assertEquals(3, fixture.requests.size());
        }
    }

    @Test
    void permissionLimitsRejectOversizedPagesFieldsAndBodiesBeforeReturningData() throws Exception {
        String entry = "{\"id\":\"reader\",\"type\":\"user\",\"role\":\"reader\"}";
        List<String> oversized = List.of(
                "{\"permissions\":[" + String.join(",", Collections.nCopies(101, entry)) + "]}",
                "{\"permissions\":[],\"nextPageToken\":\"" + "t".repeat(16_385) + "\"}",
                "{\"permissions\":[{\"id\":\"x\",\"type\":\"user\",\"role\":\"reader\",\"emailAddress\":\"" + "x".repeat(16_385) + "\"}]}");
        for (String response : oversized) {
            try (var fixture = new Fixture(exchange -> ok(response));
                 var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
                assertEquals(Failure.LIMIT_EXCEEDED, assertThrows(GoogleDriveProviderException.class,
                        () -> session.permissions("file1")).failure());
            }
        }
        try (var fixture = new Fixture(exchange -> ok("{\"permissions\":[" + entry + "]}"));
             var provider = new RestGoogleDriveProvider(new GoogleDriveProviderProperties(fixture.base.resolve("/token"),
                     fixture.base, fixture.base, fixture.base, fixture.base, null, null, null, 0, 0, 0, 0, 0, 32), mapper);
             var credential = credential(); var session = provider.open(credential)) {
            assertEquals(Failure.LIMIT_EXCEEDED, assertThrows(GoogleDriveProviderException.class,
                    () -> session.permissions("file1")).failure());
        }
    }

    @Test
    void myDriveAliasResolvesToARealParentAndIncompleteSearchCannotCompleteItsTraversal() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/files/root")) {
                return ok("{\"id\":\"own-drive-root\",\"name\":\"My Drive\",\"mimeType\":\"application/vnd.google-apps.folder\",\"version\":\"1\"}");
            }
            String query = decodedQuery(exchange);
            if (!path.equals("/files") || !query.contains("'own-drive-root' in parents")) {
                return new Response(403, new byte[0]);
            }
            if (query.contains("pageToken=partial")) return ok("{\"incompleteSearch\":true,\"files\":[]}");
            return ok("{\"nextPageToken\":\"partial\",\"files\":[" + metadata("text/plain", "1") + "]}");
        }); var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            var root = session.metadata("root");
            assertTrue(root.folder());
            assertEquals("own-drive-root", root.id());
            var first = session.listFiles(root.id(), null);
            assertEquals("file1", first.files().getFirst().id());
            assertEquals(Failure.INCONSISTENT, assertThrows(GoogleDriveProviderException.class,
                    () -> session.listFiles(root.id(), first.nextPageToken())).failure());
        }
    }

    @Test
    void sheetsAcquireAllWindowsAndExtractAfterGoogleIsUnavailable() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/files/file1")) return ok(metadata("application/vnd.google-apps.spreadsheet", "1"));
            String query = decodedQuery(exchange);
            if (!query.contains("ranges=")) return ok("""
                    {"spreadsheetId":"file1","properties":{"locale":"en_US","timeZone":"UTC"},"sheets":[
                      {"properties":{"title":"O'Brien","gridProperties":{"rowCount":501,"columnCount":1}}}]}
                    """);
            if (query.contains("A501")) return ok("""
                    {"sheets":[{"properties":{},"data":[{"startRow":500,"rowData":[{"values":[{"effectiveValue":{"numberValue":7},"formattedValue":"7"}]}]}]}]}
                    """);
            String chip = query.contains("chipRuns")
                    ? ",\"chipRuns\":[{\"chip\":{\"richLinkProperties\":{\"uri\":\"https://docs.google.com/document/d/linked1/edit\"}}}]"
                    : "";
            return ok("{\"sheets\":[{\"properties\":{},\"data\":[{\"rowData\":[{\"values\":[{\"effectiveValue\":{\"numberValue\":0}"
                    + chip + "}]}]}]}]}");
        }); var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            var acquired = session.acquire(session.metadata("file1"));
            assertEquals(SourceInputFormat.GOOGLE_SHEETS, acquired.descriptor().format());
            assertTrue(fixture.requests.stream().map(uri -> URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8))
                    .anyMatch(value -> value.contains("'O''Brien'!A501:A501")));
            fixture.server.stop(0);
            var result = new GoogleSheetsSourceContentExtractor(mapper).extract(new ByteArrayInputStream(acquired.bytes()),
                    acquired.bytes().length, acquired.filename(), acquired.descriptor());
            assertEquals("O'Brien\nA1: 0\nA501: 7", result.normalizedText());
            assertEquals(List.of(new io.memoryos.connector.GoogleDriveLinkReader.Link(
                    "https://docs.google.com/document/d/linked1/edit", "O'Brien!A1")),
                    new OfflineGoogleDriveLinkReader(mapper).read(acquired));
        }
    }

    @Test
    void docsUseNativeTabsAndSlidesExportPptx() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/files/file1")) return ok(metadata("application/vnd.google-apps.document", "1"));
            if (decodedQuery(exchange).contains("includeTabsContent=true")) return ok(document("revision1"));
            return ok("{\"documentId\":\"file1\",\"revisionId\":\"revision1\"}");
        }); var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            var acquired = session.acquire(session.metadata("file1"));
            assertEquals(SourceInputFormat.GOOGLE_DOCS, acquired.descriptor().format());
            fixture.server.stop(0);
            var result = new GoogleDocsSourceContentExtractor(mapper).extract(new ByteArrayInputStream(acquired.bytes()),
                    acquired.bytes().length, acquired.filename(), acquired.descriptor());
            assertEquals("Native document", result.normalizedText());
        }
        try (var fixture = new Fixture(exchange -> exchange.getRequestURI().getPath().endsWith("/export")
                ? new Response(200, new byte[]{'P', 'K', 3, 4}) : ok(metadata("application/vnd.google-apps.presentation", "1")));
             var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            var acquired = session.acquire(session.metadata("file1"));
            assertEquals("File.pptx", acquired.filename());
            assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", acquired.mediaType());
            assertTrue(fixture.requests.stream().map(uri -> URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8))
                    .anyMatch(value -> value.contains("/export?mimeType=" + acquired.mediaType())));
        }
    }

    @Test
    void rejectsDriveVersionAndDocsRevisionRaces() throws Exception {
        AtomicInteger metadataReads = new AtomicInteger();
        try (var fixture = new Fixture(exchange -> exchange.getRequestURI().getRawQuery().contains("alt=media")
                ? ok("content") : ok(metadata("text/plain", metadataReads.incrementAndGet() >= 3 ? "2" : "1")));
             var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            var file = session.metadata("file1");
            assertEquals(Failure.INCONSISTENT, assertThrows(GoogleDriveProviderException.class, () -> session.acquire(file)).failure());
        }
        try (var fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/files/file1")) return ok(metadata("application/vnd.google-apps.document", "1"));
            return decodedQuery(exchange).contains("includeTabsContent=true") ? ok(document("first"))
                    : ok("{\"documentId\":\"file1\",\"revisionId\":\"second\"}");
        }); var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            var file = session.metadata("file1");
            assertEquals(Failure.INCONSISTENT, assertThrows(GoogleDriveProviderException.class, () -> session.acquire(file)).failure());
        }
    }

    @Test
    void boundedResponsesRequestCountsAndTypedProviderFailuresAreExplicit() throws Exception {
        try (var fixture = new Fixture(exchange -> decodedQuery(exchange).contains("alt=media")
                ? ok("123456789") : ok(metadata("text/plain", "1")));
             var provider = provider(fixture, 8, 0); var credential = credential(); var session = provider.open(credential)) {
            var file = session.metadata("file1");
            assertEquals(Failure.LIMIT_EXCEEDED, assertThrows(GoogleDriveProviderException.class, () -> session.acquire(file)).failure());
        }
        try (var fixture = new Fixture(exchange -> exchange.getRequestURI().getPath().equals("/files/file1")
                ? ok(metadata("application/vnd.google-apps.document", "1")) : ok(document("revision")));
             var provider = provider(fixture, 0, 2); var credential = credential(); var session = provider.open(credential)) {
            var file = session.metadata("file1");
            assertEquals(Failure.LIMIT_EXCEEDED, assertThrows(GoogleDriveProviderException.class, () -> session.acquire(file)).failure());
        }
        try (var fixture = new Fixture(exchange -> new Response(410, "{}".getBytes(StandardCharsets.UTF_8)));
             var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            assertEquals(Failure.NOT_FOUND, assertThrows(GoogleDriveProviderException.class, () -> session.metadata("gone")).failure());
        }
        try (var fixture = new Fixture(exchange -> ok("{}"))) {
            fixture.tokenResponse = new Response(400, "{\"error\":\"invalid_grant\"}".getBytes(StandardCharsets.UTF_8));
            try (var provider = provider(fixture, 0, 0); var credential = credential()) {
                assertEquals(Failure.AUTHENTICATION, assertThrows(GoogleDriveProviderException.class, () -> provider.open(credential)).failure());
            }
        }
    }

    @Test
    void binaryAcquisitionPreservesAllBytesAtOneHundredMiB() throws Exception {
        byte[] block = new byte[8192];
        for (int i = 0; i < block.length; i++) block[i] = (byte) (i * 31 + 17);
        var expected = MessageDigest.getInstance("SHA-256");
        for (int size = 0; size < 104_857_600; size += block.length) expected.update(block);
        try (var fixture = new Fixture(exchange -> decodedQuery(exchange).contains("alt=media")
                ? new Response(200, block, 104_857_600) : ok(metadata("application/pdf", "1")));
             var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            byte[] acquired = session.acquire(session.metadata("file1")).bytes();
            assertEquals(104_857_600, acquired.length);
            assertArrayEquals(expected.digest(), MessageDigest.getInstance("SHA-256").digest(acquired));
        }
    }

    @Test
    void binaryAcquisitionRejectsOneByteBeyondOneHundredMiBWithoutPartialContent() throws Exception {
        byte[] block = new byte[8192];
        try (var fixture = new Fixture(exchange -> decodedQuery(exchange).contains("alt=media")
                ? new Response(200, block, 104_857_601) : ok(metadata("application/pdf", "1")));
             var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            var file = session.metadata("file1");
            assertEquals(Failure.LIMIT_EXCEEDED,
                    assertThrows(GoogleDriveProviderException.class, () -> session.acquire(file)).failure());
        }
    }

    @Test
    void configuredBinaryLimitCannotExceedOneHundredMiB() throws Exception {
        try (var fixture = new Fixture(exchange -> ok("{}"));
             var provider = provider(fixture, 104_857_601, 0); var credential = credential()) {
            assertEquals(Failure.UNAVAILABLE,
                    assertThrows(GoogleDriveProviderException.class, () -> provider.open(credential)).failure());
            assertTrue(fixture.requests.isEmpty());
        }
    }

    @Test
    void emptyPagesCompleteAndShortcutsAreNeverFollowed() throws Exception {
        try (var fixture = new Fixture(exchange -> ok("{}"));
             var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            var page = session.listFiles("folder", null);
            assertTrue(page.files().isEmpty());
            assertNull(page.nextPageToken());
            var shortcut = new GoogleDriveProvider.FileMetadata("shortcut", "Shortcut",
                    "application/vnd.google-apps.shortcut", "1", null, null, false, List.of(), null, "target");
            assertEquals(Failure.UNSUPPORTED, assertThrows(GoogleDriveProviderException.class,
                    () -> session.acquire(shortcut)).failure());
        }
    }

    @Test
    void sharedDriveBinaryAcquisitionUsesExplicitFileAndAllDrivesSupport() throws Exception {
        String file = metadata("text/plain", "1").replace("\"version\"", "\"driveId\":\"shared-drive\",\"version\"");
        try (var fixture = new Fixture(exchange -> {
            if (!exchange.getRequestURI().getPath().equals("/files/file1")
                    || !decodedQuery(exchange).contains("supportsAllDrives=true")) return new Response(403, new byte[0]);
            return decodedQuery(exchange).contains("alt=media") ? ok("Shared content") : ok(file);
        }); var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            assertEquals("Shared content", new String(session.acquire(session.metadata("file1")).bytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void refreshUsesEachCredentialsAppWithoutCrossConnectionState() throws Exception {
        try (var fixture = new Fixture(exchange -> ok("{}")); var provider = provider(fixture, 0, 0);
             var first = new GoogleDriveProvider.OAuthCredential("first.apps.googleusercontent.com", bytes("secret-one"), bytes("grant-one"));
             var second = new GoogleDriveProvider.OAuthCredential("second.apps.googleusercontent.com", bytes("secret-two"), bytes("grant-two"))) {
            try (var ignored = provider.open(first)) { assertNotNull(ignored.rotatedRefreshToken()); }
            try (var ignored = provider.open(second)) { assertNotNull(ignored.rotatedRefreshToken()); }
            assertEquals(List.of(
                    "grant_type=refresh_token&client_id=first.apps.googleusercontent.com&client_secret=secret-one&refresh_token=grant-one",
                    "grant_type=refresh_token&client_id=second.apps.googleusercontent.com&client_secret=secret-two&refresh_token=grant-two"),
                    fixture.tokenForms);
        }
    }

    @Test
    void serviceAccountsExchangeASignedAssertionForTheImpersonatedUser() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\\n" + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\\n-----END PRIVATE KEY-----\\n";
        String keyJson = "{\"type\":\"service_account\",\"private_key_id\":\"3f2a9c\",\"private_key\":\"" + pem
                + "\",\"client_email\":\"indexer@memoryos-prod.iam.gserviceaccount.com\",\"client_id\":\"1045\"}";
        try (var fixture = new Fixture(exchange -> "Bearer sa-access".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                ? ok("{\"files\":[]}") : new Response(401, new byte[0]));
             var provider = provider(fixture, 0, 0); var key = GoogleDriveServiceAccountKey.parse(keyJson);
             var credential = new GoogleDriveProvider.ServiceAccountCredential(key, "admin@example.com")) {
            fixture.tokenResponse = ok("{\"access_token\":\"sa-access\",\"token_type\":\"Bearer\",\"expires_in\":3599}");
            try (var session = provider.open(credential)) {
                assertNull(session.rotatedRefreshToken());
                assertTrue(session.listFiles("parent", null).files().isEmpty());
            }
            var form = new java.util.HashMap<String, String>();
            for (String pairText : fixture.tokenForms.getFirst().split("&")) {
                String[] parts = pairText.split("=", 2);
                form.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form.get("grant_type"));
            String[] jwt = form.get("assertion").split("\\.");
            var decoder = Base64.getUrlDecoder();
            var header = mapper.readTree(decoder.decode(jwt[0]));
            var claims = mapper.readTree(decoder.decode(jwt[1]));
            assertEquals("RS256", header.path("alg").asString());
            assertEquals("3f2a9c", header.path("kid").asString());
            assertEquals("indexer@memoryos-prod.iam.gserviceaccount.com", claims.path("iss").asString());
            assertEquals("admin@example.com", claims.path("sub").asString());
            assertEquals(fixture.base.resolve("/token").toString(), claims.path("aud").asString());
            assertEquals(String.join(" ", GoogleDriveProvider.SERVICE_ACCOUNT_SCOPES), claims.path("scope").asString());
            assertEquals(3600, claims.path("exp").asLong() - claims.path("iat").asLong());
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(pair.getPublic());
            signature.update((jwt[0] + "." + jwt[1]).getBytes(StandardCharsets.US_ASCII));
            assertTrue(signature.verify(decoder.decode(jwt[2])));
        }
    }

    @Test
    void directoryReadsUsersGroupsAndDerivedMembersPageByPage() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = decodedQuery(exchange);
            if (path.equals("/users/admin@example.com")) return ok("{\"primaryEmail\":\"Admin@Example.com\",\"isAdmin\":true,\"suspended\":false}");
            if (path.equals("/users/member@example.com")) return new Response(403, new byte[0]);
            if (path.equals("/groups") && query.contains("domain=example.com") && !query.contains("pageToken"))
                return ok("{\"nextPageToken\":\"groups-next\",\"groups\":[{\"email\":\"Sales@example.com\"}]}");
            if (path.equals("/groups") && query.contains("pageToken=groups-next")) return ok("{\"groups\":[{\"email\":\"all@example.com\"}]}");
            if (path.equals("/groups/sales@example.com/members") && query.contains("includeDerivedMembership=true"))
                return ok("{\"members\":[{\"email\":\"Ann@example.com\",\"type\":\"USER\",\"status\":\"ACTIVE\"},"
                        + "{\"email\":\"nested@example.com\",\"type\":\"GROUP\"},"
                        + "{\"email\":\"gone@example.com\",\"type\":\"USER\",\"status\":\"SUSPENDED\"},"
                        + "{\"id\":\"C01\",\"type\":\"CUSTOMER\"}]}");
            return new Response(404, new byte[0]);
        }); var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            var admin = session.directoryUser("admin@example.com");
            assertEquals("admin@example.com", admin.primaryEmail());
            assertTrue(admin.admin());
            assertFalse(admin.suspended());
            assertEquals(Failure.ACCESS_DENIED, assertThrows(GoogleDriveProviderException.class,
                    () -> session.directoryUser("member@example.com")).failure());
            var first = session.groups("example.com", null);
            assertEquals(List.of("sales@example.com"), first.emails());
            assertEquals("groups-next", first.nextPageToken());
            var last = session.groups("example.com", first.nextPageToken());
            assertEquals(List.of("all@example.com"), last.emails());
            assertNull(last.nextPageToken());
            var members = session.groupMembers("sales@example.com", null);
            assertEquals(List.of("ann@example.com"), members.emails());
            assertTrue(members.wholeDomain());
            assertNull(members.nextPageToken());
        }
    }

    @Test
    void providerRedirectCannotForwardAuthorizationToAnotherHost() throws Exception {
        AtomicInteger contacted = new AtomicInteger();
        try (var destination = new Fixture(exchange -> { contacted.incrementAndGet(); return ok("{}"); });
             var fixture = new Fixture(exchange -> {
                 exchange.getResponseHeaders().set("Location", destination.base + "/stolen");
                 return new Response(302, new byte[0]);
             }); var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            assertEquals(Failure.MALFORMED, assertThrows(GoogleDriveProviderException.class, () -> session.metadata("file1")).failure());
            assertEquals(0, contacted.get());
        }
    }

    @Test
    void readonlyDocsWithoutEditRevisionStillAcquireWithDriveVersionFence() throws Exception {
        String readonly = document("unused").replace("\"revisionId\":\"unused\",", "");
        try (var fixture = new Fixture(exchange -> exchange.getRequestURI().getPath().equals("/files/file1")
                ? ok(metadata("application/vnd.google-apps.document", "1")) : ok(readonly));
             var provider = provider(fixture, 0, 0); var credential = credential(); var session = provider.open(credential)) {
            var acquired = session.acquire(session.metadata("file1"));
            fixture.server.stop(0);
            var result = new GoogleDocsSourceContentExtractor(mapper).extract(new ByteArrayInputStream(acquired.bytes()),
                    acquired.bytes().length, acquired.filename(), acquired.descriptor());
            assertEquals("Native document", result.normalizedText());
            assertEquals("1", acquired.descriptor().providerVersion());
        }
    }

    private RestGoogleDriveProvider provider(Fixture fixture, int binaryLimit, int requests) {
        return new RestGoogleDriveProvider(new GoogleDriveProviderProperties(fixture.base.resolve("/token"),
                fixture.base, fixture.base, fixture.base, fixture.base, null, null, null, 0, requests, 0, 0, binaryLimit, 0), mapper);
    }

    private static GoogleDriveProvider.Credential credential() {
        return new GoogleDriveProvider.OAuthCredential("client.apps.googleusercontent.com", bytes("secret"), bytes("refresh"));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static String metadata(String type, String version) {
        return "{\"id\":\"file1\",\"name\":\"File\",\"mimeType\":\"" + type + "\",\"version\":\"" + version + "\"}";
    }

    private static String document(String revision) {
        return "{\"documentId\":\"file1\",\"revisionId\":\"" + revision + "\",\"tabs\":[{\"tabProperties\":{\"tabId\":\"tab1\"},"
                + "\"documentTab\":{\"body\":{\"content\":[{\"paragraph\":{\"elements\":[{\"textRun\":{\"content\":\"Native document\\n\"}}]}}]}}}]}";
    }

    private static String decodedQuery(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        return query == null ? "" : URLDecoder.decode(query, StandardCharsets.UTF_8);
    }

    private static Response ok(String body) { return new Response(200, body.getBytes(StandardCharsets.UTF_8)); }
    private record Response(int status, byte[] body, int sizeBytes) {
        Response(int status, byte[] body) { this(status, body, body.length); }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final URI base;
        final List<URI> requests = Collections.synchronizedList(new ArrayList<>());
        final List<String> tokenForms = Collections.synchronizedList(new ArrayList<>());
        volatile Response tokenResponse = ok("{\"access_token\":\"access\",\"token_type\":\"Bearer\",\"refresh_token\":\"rotated\"}");

        Fixture(Function<HttpExchange, Response> responder) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            server.createContext("/", exchange -> {
                requests.add(exchange.getRequestURI());
                if (exchange.getRequestURI().getPath().equals("/token")) {
                    tokenForms.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                }
                Response response = exchange.getRequestURI().getPath().equals("/token") ? tokenResponse : responder.apply(exchange);
                exchange.sendResponseHeaders(response.status(), response.sizeBytes());
                try (var output = exchange.getResponseBody()) {
                    for (int written = 0; written < response.sizeBytes();) {
                        int count = Math.min(response.body().length, response.sizeBytes() - written);
                        output.write(response.body(), 0, count);
                        written += count;
                    }
                }
                exchange.close();
            });
            server.start();
        }
        @Override public void close() { server.stop(0); }
    }
}
