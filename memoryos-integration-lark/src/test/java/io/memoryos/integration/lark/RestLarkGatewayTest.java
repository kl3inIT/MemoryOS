package io.memoryos.integration.lark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.memoryos.connector.LarkGateway;
import io.memoryos.connector.LarkGateway.Collaborator;
import io.memoryos.connector.LarkGateway.ObjectType;
import io.memoryos.connector.LarkProviderException;
import io.memoryos.connector.LarkProviderException.Failure;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The gateway on the wire. The answers under {@code src/test/resources/lark} were recorded from a Lark international
 * test tenant on 2026-10-12, with names and emails replaced.
 */
class RestLarkGatewayTest {
    private static final String SPACE = "7695460898437074458";
    private static final String FOLDER = "folder-drive-test";
    private static final String TOKEN = "{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"t-test\",\"expire\":7200}";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void opensWithItsOwnTenantTokenAndSendsItWithEveryRequest() throws Exception {
        var bodies = Collections.synchronizedList(new ArrayList<String>());
        try (var fixture = new Fixture(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/open-apis/auth/v3/tenant_access_token/internal")) {
                bodies.add(body(exchange));
                assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
                return ok(TOKEN);
            }
            assertEquals("/open-apis/bot/v3/info", exchange.getRequestURI().getPath());
            assertEquals("Bearer t-test", exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("MemoryOS-LarkConnector/1.0", exchange.getRequestHeaders().getFirst("User-Agent"));
            return ok(resource("bot-info"));
        }); var gateway = gateway(fixture); var session = gateway.open(credential())) {
            var bot = session.bot();
            assertEquals("MemoryOS Test", bot.appName());
            assertEquals("ou_365b28d4ddf03d6ab0f8000a0f68443f", bot.openId());
            session.bot();
        }
        // One token for the session, asked with the credential and nothing cached from elsewhere.
        assertEquals(1, bodies.size());
        JsonNode sent = mapper.readTree(bodies.getFirst());
        assertEquals("cli_test", sent.path("app_id").asString());
        assertEquals("app-secret", sent.path("app_secret").asString());
    }

    @Test
    void aRefusedSecretIsAnAuthenticationFailureAndIsNeverHiddenByAnEarlierToken() throws Exception {
        var refuse = new boolean[1];
        try (var fixture = new Fixture(_ -> refuse[0]
                ? new Response(400, "{\"code\":10014,\"msg\":\"app secret invalid\"}".getBytes(StandardCharsets.UTF_8))
                : ok(TOKEN)); var gateway = gateway(fixture)) {
            gateway.open(credential()).close();
            refuse[0] = true;
            var exception = assertThrows(LarkProviderException.class, () -> gateway.open(credential()));
            assertEquals(Failure.AUTHENTICATION, exception.failure());
            assertFalse(exception.getMessage().contains("secret invalid"));
        }
    }

    @Test
    void readsSpacesAndTheNodesOfASpace() throws Exception {
        try (var fixture = lark(Map.of(
                "/open-apis/wiki/v2/spaces", "wiki-spaces",
                "/open-apis/wiki/v2/spaces/" + SPACE, "wiki-space",
                "/open-apis/wiki/v2/spaces/" + SPACE + "/nodes", "wiki-nodes-root"));
             var gateway = gateway(fixture); var session = gateway.open(credential())) {
            var spaces = session.spaces(null);
            assertEquals(1, spaces.spaces().size());
            assertEquals(SPACE, spaces.spaces().getFirst().spaceId());
            assertEquals("MemoryOS Test", spaces.spaces().getFirst().name());
            assertNull(spaces.pageToken());
            assertEquals("private", session.space(SPACE).visibility());

            var nodes = session.nodes(SPACE, null, null);
            assertEquals(3, nodes.nodes().size());
            var first = nodes.nodes().getFirst();
            assertEquals("Sổ tay nhân viên", first.title());
            assertEquals(ObjectType.DOCX, first.objType());
            assertEquals("docx-so-tay", first.objToken());
            assertTrue(first.hasChild());
            assertNotNull(first.url());
            assertTrue(first.url().endsWith("/wiki/node-so-tay"));
            assertNull(first.parentNodeToken());
            assertNotNull(first.objEditedAt());
            assertTrue(first.objEditedAt().isAfter(Instant.parse("2026-10-11T00:00:00Z")));
            assertTrue(fixture.requests.stream().anyMatch(uri -> uri.getPath().endsWith("/nodes")
                    && uri.getQuery().contains("page_size=50") && !uri.getQuery().contains("parent_node_token")));
        }
    }

    @Test
    void aChildListingNamesItsParentAndKeepsTheSheetType() throws Exception {
        try (var fixture = lark(Map.of("/open-apis/wiki/v2/spaces/" + SPACE + "/nodes", "wiki-nodes-kinh-doanh"));
             var gateway = gateway(fixture); var session = gateway.open(credential())) {
            var nodes = session.nodes(SPACE, "node-kinh-doanh", null).nodes();
            assertEquals(List.of(ObjectType.DOCX, ObjectType.SHEET), nodes.stream().map(LarkGateway.Node::objType).toList());
            assertEquals("node-kinh-doanh", nodes.getFirst().parentNodeToken());
            assertTrue(fixture.requests.getLast().getQuery().contains("parent_node_token=node-kinh-doanh"));
        }
    }

    @Test
    void readsAFolderByItsTokenAndItsFiles() throws Exception {
        try (var fixture = lark(Map.of(
                "/open-apis/drive/v1/metas/batch_query", "drive-meta-folder",
                "/open-apis/drive/v1/files", "drive-files"));
             var gateway = gateway(fixture); var session = gateway.open(credential())) {
            assertEquals("MemoryOS Drive Test", session.folder(FOLDER).name());
            var files = session.files(FOLDER, null);
            assertEquals(6, files.files().size());
            assertEquals(3, files.files().stream().filter(file -> file.type() == ObjectType.DOCX).count());
            assertEquals(3, files.files().stream().filter(file -> file.type() == ObjectType.FILE).count());
            var pdf = files.files().stream().filter(file -> file.name().equals("tai-chinh-q3.pdf")).findFirst().orElseThrow();
            assertEquals(FOLDER, pdf.parentToken());
            assertNotNull(pdf.url());
            assertNotNull(pdf.modifiedAt());
            assertTrue(fixture.requests.getLast().getQuery().contains("folder_token=" + FOLDER));
        }
    }

    @Test
    void aFolderLarkDoesNotReturnIsNotFound() throws Exception {
        try (var fixture = lark(Map.of("/open-apis/drive/v1/metas/batch_query", "drive-meta-missing"));
             var gateway = gateway(fixture); var session = gateway.open(credential())) {
            assertEquals(Failure.NOT_FOUND,
                    assertThrows(LarkProviderException.class, () -> session.folder("fldusNoSuchFolder000000000000")).failure());
        }
    }

    @Test
    void aDocxSnapshotKeepsEveryBlockWithItsTable() throws Exception {
        try (var fixture = lark(Map.of("/open-apis/docx/v1/documents/docx-doanh-thu-q3/blocks", "docx-blocks"));
             var gateway = gateway(fixture); var session = gateway.open(credential())) {
            var snapshot = session.docx("docx-doanh-thu-q3");
            assertEquals(RestLarkGateway.DOCX_SCHEMA, snapshot.schema());
            JsonNode root = mapper.readTree(snapshot.bytes());
            assertEquals(RestLarkGateway.DOCX_SCHEMA, root.path("schema").asString());
            assertEquals("docx-doanh-thu-q3", root.path("documentId").asString());
            assertEquals(30, root.path("blocks").size());
            var types = new ArrayList<Integer>();
            root.path("blocks").forEach(block -> types.add(block.path("block_type").asInt()));
            assertEquals(1, types.stream().filter(type -> type == 31).count());
            assertEquals(12, types.stream().filter(type -> type == 32).count());
            var table = root.path("blocks").valueStream().filter(block -> block.path("block_type").asInt() == 31)
                    .findFirst().orElseThrow();
            assertEquals(3, table.path("table").path("property").path("column_size").asInt());
            assertEquals(4, table.path("table").path("property").path("row_size").asInt());
            assertEquals("Báo cáo doanh thu Q3 2026", root.path("blocks").get(1).path("heading1").path("elements").get(0)
                    .path("text_run").path("content").asString());
        }
    }

    @Test
    void aDocxSnapshotFollowsEveryPageAndStopsAtItsBlockBound() throws Exception {
        Function<HttpExchange, Response> pages = exchange -> {
            if (token(exchange)) return ok(TOKEN);
            boolean second = exchange.getRequestURI().getQuery().contains("page_token=next");
            return ok("{\"code\":0,\"data\":{\"items\":[{\"block_id\":\"" + (second ? "b2" : "b1")
                    + "\",\"block_type\":2,\"text\":{\"elements\":[]}}],\"has_more\":" + !second
                    + ",\"page_token\":\"next\"}}");
        };
        try (var fixture = new Fixture(pages); var gateway = gateway(fixture); var session = gateway.open(credential())) {
            JsonNode root = mapper.readTree(session.docx("doc").bytes());
            assertEquals(List.of("b1", "b2"), root.path("blocks").valueStream().map(b -> b.path("block_id").asString()).toList());
        }
        try (var fixture = new Fixture(pages);
             var gateway = new RestLarkGateway(properties(fixture, 0, 1), new SimpleMeterRegistry());
             var session = gateway.open(credential())) {
            assertEquals(Failure.LIMIT_EXCEEDED,
                    assertThrows(LarkProviderException.class, () -> session.docx("doc")).failure());
        }
    }

    @Test
    void aSheetSnapshotKeepsTheValuesWithoutTheEmptyGrid() throws Exception {
        try (var fixture = lark(Map.of(
                "/open-apis/sheets/v3/spreadsheets/sheet-bang-gia/sheets/query", "sheet-query",
                "/open-apis/sheets/v2/spreadsheets/sheet-bang-gia/values/9096cf!A1:T200", "sheet-values"));
             var gateway = gateway(fixture); var session = gateway.open(credential())) {
            JsonNode root = mapper.readTree(session.sheet("sheet-bang-gia").bytes());
            assertEquals(RestLarkGateway.SHEET_SCHEMA, root.path("schema").asString());
            JsonNode sheet = root.path("sheets").get(0);
            assertEquals("9096cf", sheet.path("sheetId").asString());
            assertFalse(sheet.path("truncated").asBoolean());
            // Lark answered 200 rows of 20 cells; six rows of four hold something.
            assertEquals(6, sheet.path("values").size());
            assertEquals(4, sheet.path("values").get(0).size());
            assertEquals("Dịch vụ", sheet.path("values").get(0).get(0).asString());
            assertEquals(6500000, sheet.path("values").get(1).get(2).asInt());
        }
    }

    @Test
    void columnLettersFollowTheSpreadsheetConvention() {
        assertEquals("A", RestLarkGateway.column(1));
        assertEquals("T", RestLarkGateway.column(20));
        assertEquals("Z", RestLarkGateway.column(26));
        assertEquals("AA", RestLarkGateway.column(27));
        assertEquals("ZZ", RestLarkGateway.column(702));
    }

    @Test
    void permissionsCarryEveryCollaboratorAndTheLinkSetting() throws Exception {
        try (var fixture = lark(Map.of(
                "/open-apis/drive/v1/permissions/docx-nghi-phep/members", "permission-members-wiki-page",
                "/open-apis/drive/v1/permissions/docx-nghi-phep/public", "permission-public"));
             var gateway = gateway(fixture); var session = gateway.open(credential())) {
            var permissions = session.permissions("docx-nghi-phep", ObjectType.DOCX);
            assertEquals(List.of(
                    new Collaborator("wikispaceid", SPACE, "edit"),
                    new Collaborator("wikispaceid", SPACE, "view"),
                    new Collaborator("openid", "ou_a8c60d2416a98fd991f1271fd84de633", "full_access")),
                    permissions.collaborators());
            assertEquals("tenant_readable", permissions.linkShare());
            assertTrue(fixture.requests.stream().allMatch(uri -> uri.getPath().contains("tenant_access_token")
                    || uri.getQuery().contains("type=docx")));
        }
    }

    @Test
    void anAppCollaboratorIsReportedAsLarkSendsIt() throws Exception {
        try (var fixture = lark(Map.of(
                "/open-apis/drive/v1/permissions/doc/members", "permission-members-drive-doc",
                "/open-apis/drive/v1/permissions/doc/public", "permission-public"));
             var gateway = gateway(fixture); var session = gateway.open(credential())) {
            assertTrue(session.permissions("doc", ObjectType.DOCX).collaborators()
                    .contains(new Collaborator("appid", "cli_aa4102bd7238de15", "view")));
        }
    }

    @Test
    void usersCarryTheirEmailAndSpaceMembersTheirRole() throws Exception {
        try (var fixture = lark(Map.of(
                "/open-apis/contact/v3/users/batch", "contact-users-batch",
                "/open-apis/wiki/v2/spaces/" + SPACE + "/members", "wiki-space-members"));
             var gateway = gateway(fixture); var session = gateway.open(credential())) {
            var users = session.users(List.of("ou_a8c60d2416a98fd991f1271fd84de633"));
            assertEquals(1, users.size());
            assertEquals("owner@minhan.example", users.getFirst().email());
            assertTrue(users.getFirst().active());
            assertFalse(users.getFirst().toString().contains("owner@"));
            assertTrue(fixture.requests.stream().anyMatch(uri -> uri.getPath().endsWith("/users/batch")
                    && uri.getQuery().contains("user_ids=ou_a8c60d2416a98fd991f1271fd84de633")
                    && uri.getQuery().contains("user_id_type=open_id")));

            var members = session.spaceMembers(SPACE, null).members();
            assertEquals(List.of(
                    new Collaborator("openid", "ou_a8c60d2416a98fd991f1271fd84de633", "admin"),
                    new Collaborator("openid", "ou_365b28d4ddf03d6ab0f8000a0f68443f", "member")), members);
            assertTrue(session.users(List.of()).isEmpty());
        }
    }

    @Test
    void aChatADepartmentAndTheDepartmentsBelowItNameTheirPeopleByOpenId() throws Exception {
        try (var fixture = lark(Map.of(
                "/open-apis/im/v1/chats/oc_kinh-doanh/members", "chat-members",
                "/open-apis/contact/v3/departments/od-kinh-doanh/children", "department-children",
                "/open-apis/contact/v3/users/find_by_department", "department-users"));
             var gateway = gateway(fixture); var session = gateway.open(credential())) {
            var chat = session.chatMembers("oc_kinh-doanh", null);
            assertEquals(List.of("ou_minh-tran", "ou_lan-nguyen"), chat.openIds());
            assertNull(chat.pageToken());

            // Every department below, at any depth, so a department counts the people of its sub-departments.
            assertEquals(List.of("od-kinh-doanh-mien-bac"),
                    session.departmentChildren("od-kinh-doanh", null).departmentIds());
            assertTrue(fixture.requests.stream().anyMatch(uri -> uri.getPath().endsWith("/children")
                    && uri.getQuery().contains("fetch_child=true")
                    && uri.getQuery().contains("department_id_type=open_department_id")));

            assertEquals(List.of("ou_lan-nguyen"),
                    session.departmentMembers("od-kinh-doanh-mien-bac", null).openIds());
            assertTrue(fixture.requests.stream().anyMatch(uri -> uri.getPath().endsWith("/find_by_department")
                    && uri.getQuery().contains("department_id=od-kinh-doanh-mien-bac")
                    && uri.getQuery().contains("user_id_type=open_id")));
        }
    }

    @Test
    void aRefusedScopeNamesTheScopesAndARefusedDocumentNamesNone() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            if (token(exchange)) return ok(TOKEN);
            return exchange.getRequestURI().getPath().contains("/im/v1/chats/")
                    ? new Response(400, bytes("refused-scope")) : new Response(403, bytes("refused-document"));
        }); var gateway = gateway(fixture); var session = gateway.open(credential())) {
            var scope = assertThrows(LarkProviderException.class, () -> session.chatMembers("oc_x", null));
            assertEquals(Failure.AUTHORIZATION, scope.failure());
            assertEquals(Set.of("im:chat:readonly", "im:chat", "im:chat.group_info:readonly", "im:chat.members:read"),
                    scope.missingScopes());
            assertFalse(scope.getMessage().contains("Access denied"));

            var document = assertThrows(LarkProviderException.class, () -> session.docx("docx-bi-mat"));
            assertEquals(Failure.AUTHORIZATION, document.failure());
            assertTrue(document.missingScopes().isEmpty());
        }
    }

    @Test
    void theScopeProbeReportsOnlyWhatLarkRefusesToTheTenantToken() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            if (token(exchange)) return ok(TOKEN);
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/im/v1/chats/")) return new Response(400, bytes("refused-scope"));
            if (path.endsWith("/wiki/v2/spaces")) return ok(resource("wiki-spaces"));
            if (path.endsWith("/drive/v1/files")) return ok("{\"code\":0,\"data\":{\"files\":[],\"has_more\":false}}");
            // A granted scope fails on the probe's token instead.
            return new Response(404, "{\"code\":1310214,\"msg\":\"not found\"}".getBytes(StandardCharsets.UTF_8));
        }); var gateway = gateway(fixture); var session = gateway.open(credential())) {
            assertEquals(Set.of("im:chat:readonly"), session.missingScopes());
        }
    }

    @Test
    void classifiesAStatusWithoutEchoingLarkText() throws Exception {
        Map<Integer, Failure> expected = Map.of(401, Failure.AUTHENTICATION, 403, Failure.AUTHORIZATION,
                404, Failure.NOT_FOUND, 429, Failure.QUOTA, 500, Failure.UNAVAILABLE, 503, Failure.UNAVAILABLE,
                400, Failure.MALFORMED, 302, Failure.MALFORMED);
        for (var entry : expected.entrySet()) {
            var followed = new boolean[1];
            try (var fixture = new Fixture(exchange -> {
                if (token(exchange)) return ok(TOKEN);
                if (exchange.getRequestURI().getPath().equals("/elsewhere")) followed[0] = true;
                exchange.getResponseHeaders().add("Location", "/elsewhere");
                return new Response(entry.getKey(), "{\"code\":230001,\"msg\":\"internal detail\"}".getBytes(StandardCharsets.UTF_8));
            }); var gateway = gateway(fixture); var session = gateway.open(credential())) {
                var exception = assertThrows(LarkProviderException.class, () -> session.spaces(null));
                assertEquals(entry.getValue(), exception.failure(), "status " + entry.getKey());
                assertFalse(exception.getMessage().contains("internal detail"));
                assertFalse(followed[0], "a redirect was followed");
            }
        }
    }

    @Test
    void larkCodesDecideBeforeTheStatus() {
        assertEquals(Failure.AUTHENTICATION, RestLarkGateway.failure(400, 99991663, Set.of()).failure());
        assertEquals(Failure.AUTHENTICATION, RestLarkGateway.failure(200, 10014, Set.of()).failure());
        assertEquals(Failure.AUTHORIZATION, RestLarkGateway.failure(400, 99991679, Set.of()).failure());
        assertEquals(Failure.QUOTA, RestLarkGateway.failure(400, 99991400, Set.of()).failure());
        assertEquals(Failure.NOT_FOUND, RestLarkGateway.failure(400, 1770002, Set.of()).failure());
        assertEquals(Failure.MALFORMED, RestLarkGateway.failure(400, 99992402, Set.of()).failure());
    }

    @Test
    void aThrottledAnswerCarriesTheWaitLarkAskedFor() throws Exception {
        for (String header : List.of("Retry-After", "x-ogw-ratelimit-reset")) {
            try (var fixture = new Fixture(exchange -> {
                if (token(exchange)) return ok(TOKEN);
                exchange.getResponseHeaders().add(header, "42");
                return new Response(429, new byte[0]);
            }); var gateway = gateway(fixture); var session = gateway.open(credential())) {
                var exception = assertThrows(LarkProviderException.class, () -> session.spaces(null));
                assertEquals(Failure.QUOTA, exception.failure());
                assertEquals(Duration.ofSeconds(42), exception.retryAfter(), header);
            }
        }
    }

    @Test
    void anAnswerOverItsBoundIsRefused() throws Exception {
        try (var fixture = lark(Map.of("/open-apis/wiki/v2/spaces/" + SPACE + "/nodes", "wiki-nodes-root"));
             var gateway = new RestLarkGateway(properties(fixture, 1024, 0), new SimpleMeterRegistry());
             var session = gateway.open(credential())) {
            assertEquals(Failure.LIMIT_EXCEEDED,
                    assertThrows(LarkProviderException.class, () -> session.nodes(SPACE, null, null)).failure());
        }
    }

    @Test
    void downloadsAFileWithItsNameAndRefusesOneOverTheCallersBound() throws Exception {
        byte[] pdf = "%PDF-1.7 test".getBytes(StandardCharsets.UTF_8);
        for (boolean chunked : List.of(false, true)) {
            try (var fixture = new Fixture(exchange -> {
                if (token(exchange)) return ok(TOKEN);
                assertEquals("/open-apis/drive/v1/files/boxFile/download", exchange.getRequestURI().getPath());
                exchange.getResponseHeaders().add("Content-Type", "application/pdf");
                exchange.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"report.pdf\"");
                return new Response(200, pdf, chunked);
            }); var gateway = gateway(fixture); var session = gateway.open(credential())) {
                var content = session.download("boxFile", 1024);
                assertEquals("report.pdf", content.filename());
                assertEquals("application/pdf", content.mediaType());
                assertArrayEquals(pdf, content.bytes());
                assertEquals(Failure.LIMIT_EXCEEDED,
                        assertThrows(LarkProviderException.class, () -> session.download("boxFile", 4)).failure());
            }
        }
    }

    @Test
    void anAnswerThatDoesNotComeInTimeEndsTheExchange() throws Exception {
        try (var fixture = new Fixture(exchange -> {
            if (token(exchange)) return ok(TOKEN);
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ok(resource("wiki-spaces"));
        }); var gateway = new RestLarkGateway(new LarkProviderProperties(fixture.base, Duration.ofSeconds(1),
                Duration.ofMillis(300), null, 0, 0, 0, 0, 0, 0, null), new SimpleMeterRegistry());
             var session = gateway.open(credential())) {
            long started = System.nanoTime();
            assertEquals(Failure.UNAVAILABLE,
                    assertThrows(LarkProviderException.class, () -> session.spaces(null)).failure());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
        }
    }

    @Test
    void wrongSettingsFailTheOpenAndNotTheStart() {
        var gateway = new RestLarkGateway(new LarkProviderProperties(URI.create("http://example.com"), null, null, null,
                0, 0, 0, 0, 0, 0, null), new SimpleMeterRegistry());
        assertEquals(Failure.UNAVAILABLE,
                assertThrows(LarkProviderException.class, () -> gateway.open(credential())).failure());
        gateway.close();
    }

    private static RestLarkGateway gateway(Fixture fixture) {
        return new RestLarkGateway(properties(fixture, 0, 0), new SimpleMeterRegistry());
    }

    private static LarkProviderProperties properties(Fixture fixture, int maxResponseBytes, int maxBlocks) {
        return new LarkProviderProperties(fixture.base, null, null, null, maxResponseBytes, 0, 0, maxBlocks, 0, 0, null);
    }

    private static LarkGateway.Credential credential() {
        return new LarkGateway.Credential(LarkGateway.Region.LARK, "cli_test", "app-secret".getBytes(StandardCharsets.UTF_8));
    }

    /** A Lark that answers the token request and each named path with its recorded answer. */
    private static Fixture lark(Map<String, String> answers) throws IOException {
        return new Fixture(exchange -> {
            if (token(exchange)) return ok(TOKEN);
            String name = answers.get(exchange.getRequestURI().getPath());
            return name == null ? new Response(404, "{\"code\":404,\"msg\":\"no fixture\"}".getBytes(StandardCharsets.UTF_8))
                    : ok(resource(name));
        });
    }

    private static boolean token(HttpExchange exchange) {
        return exchange.getRequestURI().getPath().equals("/open-apis/auth/v3/tenant_access_token/internal");
    }

    private static String body(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static String resource(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String name) {
        try (var stream = RestLarkGatewayTest.class.getResourceAsStream("/lark/" + name + ".json")) {
            if (stream == null) throw new IllegalArgumentException("no fixture " + name);
            return stream.readAllBytes();
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
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
                if (!exchange.getResponseHeaders().containsKey("Content-Type"))
                    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                if (response.body().length == 0 && !response.chunked()) {
                    exchange.sendResponseHeaders(response.status(), -1);
                } else {
                    exchange.sendResponseHeaders(response.status(), response.chunked() ? 0 : response.body().length);
                    try (var output = exchange.getResponseBody()) {
                        output.write(response.body());
                    }
                }
                exchange.close();
            });
            server.start();
        }

        @Override public void close() { server.stop(0); }
    }
}
