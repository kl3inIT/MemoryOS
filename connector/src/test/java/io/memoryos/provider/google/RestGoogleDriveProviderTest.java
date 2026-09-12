package io.memoryos.provider.google;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.connector.GoogleDriveProvider;
import io.memoryos.connector.GoogleDriveProviderException;
import io.memoryos.connector.GoogleDriveProviderException.Failure;
import io.memoryos.connector.SourceInputFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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
             var first = new GoogleDriveProvider.Credential("first.apps.googleusercontent.com", bytes("secret-one"), bytes("grant-one"));
             var second = new GoogleDriveProvider.Credential("second.apps.googleusercontent.com", bytes("secret-two"), bytes("grant-two"))) {
            try (var ignored = provider.open(first)) { assertNotNull(ignored.rotatedRefreshToken()); }
            try (var ignored = provider.open(second)) { assertNotNull(ignored.rotatedRefreshToken()); }
            assertEquals(List.of(
                    "grant_type=refresh_token&client_id=first.apps.googleusercontent.com&client_secret=secret-one&refresh_token=grant-one",
                    "grant_type=refresh_token&client_id=second.apps.googleusercontent.com&client_secret=secret-two&refresh_token=grant-two"),
                    fixture.tokenForms);
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
                fixture.base, fixture.base, fixture.base, null, null, null, 0, requests, 0, 0, binaryLimit, 0), mapper);
    }

    private static GoogleDriveProvider.Credential credential() {
        return new GoogleDriveProvider.Credential("client.apps.googleusercontent.com", bytes("secret"), bytes("refresh"));
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
