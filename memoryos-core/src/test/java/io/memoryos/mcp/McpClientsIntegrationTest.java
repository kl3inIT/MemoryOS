package io.memoryos.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.catalina.startup.Tomcat;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Exercises the real SDK client and transport against an in-process Streamable HTTP MCP server. */
class McpClientsIntegrationTest {
    private static final Map<String, String> GOOD = Map.of("Authorization", "Bearer good");
    private static final Duration DEADLINE = Duration.ofSeconds(10);

    private static Tomcat tomcat;
    private static McpSyncServer server;
    private static String url;
    private final McpClients clients = new McpClients(Duration.ofSeconds(2), Duration.ofSeconds(10));

    @BeforeAll
    static void start() throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
        server = McpServer.sync(transport)
                .serverInfo("fixture", "1")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).resources(false, false).build())
                .tools(
                        tool("echo", true, (arguments) -> McpSchema.CallToolResult.builder()
                                .addTextContent("echo: " + arguments.get("text")).build()),
                        tool("fail", false, (arguments) -> McpSchema.CallToolResult.builder()
                                .addTextContent("bad input").isError(true).build()),
                        tool("structured", true, (arguments) -> McpSchema.CallToolResult.builder()
                                .structuredContent(Map.of("count", 2)).build()),
                        tool("slow", true, (arguments) -> {
                            try {
                                Thread.sleep(3_000);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                            }
                            return McpSchema.CallToolResult.builder().addTextContent("late").build();
                        }),
                        tool("picker", true, Map.of("ui", Map.of("resourceUri", "ui://fixture/picker")),
                                (arguments) -> McpSchema.CallToolResult.builder().addTextContent("picked").build()),
                        tool("select", false, Map.of("ui", Map.of("visibility", List.of("app"))),
                                (arguments) -> McpSchema.CallToolResult.builder().addTextContent("selected").build()),
                        tool("legacy", true, Map.of("openai/outputTemplate", "ui://fixture/legacy"),
                                (arguments) -> McpSchema.CallToolResult.builder().addTextContent("legacy").build()))
                .resources(
                        resource("ui://fixture/picker", null, "text/html;profile=mcp-app", "<html>picker</html>",
                                Map.of("ui", Map.of("csp", Map.of("connectDomains", List.of("https://api.example"))))),
                        resource("ui://fixture/listed", Map.of("ui", Map.of("prefersBorder", true)),
                                "text/html;profile=mcp-app", "<html>listed</html>", null),
                        resource("ui://fixture/plain", null, "text/plain", "plain", null))
                .build();
        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("mcp-tomcat").toString());
        tomcat.setPort(0);
        var context = tomcat.addContext("", null);
        var servlet = Tomcat.addServlet(context, "mcp", new BearerServlet(transport));
        servlet.setAsyncSupported(true);
        servlet.addMapping("/mcp");
        tomcat.getConnector();
        tomcat.start();
        url = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/mcp";
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) server.closeGracefully();
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    @Test
    void listsToolsWithSchemasAndAnnotations() {
        try (var session = clients.open(url, GOOD, DEADLINE)) {
            var tools = session.listTools();

            assertEquals(List.of("echo", "fail", "structured", "slow", "picker", "select", "legacy"),
                    tools.stream().map(McpToolDescriptor::name).toList());
            var echo = tools.getFirst();
            assertEquals(Boolean.TRUE, echo.readOnlyHint());
            assertEquals("object", echo.inputSchema().get("type"));
            assertEquals(Boolean.FALSE, tools.get(1).readOnlyHint());
        }
    }

    @Test
    void listsToolViewsAndWhoMayCallThem() {
        try (var session = clients.open(url, GOOD, DEADLINE)) {
            var tools = session.listTools();
            var echo = tools.getFirst();
            assertEquals(null, echo.appResourceUri());
            assertTrue(echo.modelVisible() && echo.appVisible());
            assertEquals("ui://fixture/picker", tools.get(4).appResourceUri());
            var select = tools.get(5);
            assertFalse(select.modelVisible());
            assertTrue(select.appVisible());
            assertEquals("ui://fixture/legacy", tools.get(6).appResourceUri());
        }
    }

    @Test
    void readsViewsWithTheirMetadataAndRefusesOtherDocuments() {
        try (var session = clients.open(url, GOOD, DEADLINE)) {
            var picker = session.readApp("ui://fixture/picker");
            assertEquals("<html>picker</html>", picker.html());
            assertEquals(Map.of("connectDomains", List.of("https://api.example")), picker.meta().get("csp"));
            // Without metadata on the read content, the resource's listing gives it.
            assertEquals(Boolean.TRUE, session.readApp("ui://fixture/listed").meta().get("prefersBorder"));
            assertCode("MCP_APP_RESOURCE_INVALID", () -> session.readApp("ui://fixture/plain"));
            assertTrue(session.listResources(null).contains("ui://fixture/picker"));
            assertTrue(session.readResource("ui://fixture/plain").contains("plain"));
        }
    }

    @Test
    void callsToolsAndConvertsResultsForTheModel() {
        try (var session = clients.open(url, GOOD, DEADLINE)) {
            var echo = session.call("echo", Map.of("text", "hi"));
            assertEquals("echo: hi", echo.text());
            assertFalse(echo.error());
            assertTrue(echo.result().contains("\"echo: hi\""), echo.result());
            var failed = session.call("fail", Map.of("text", "x"));
            assertEquals("bad input", failed.text());
            assertTrue(failed.error());
            var structured = session.call("structured", Map.of("text", "x"));
            assertEquals("{\"count\":2}", structured.text());
            // A view receives the whole result, its structured content included.
            assertTrue(structured.result().contains("\"structuredContent\":{\"count\":2}"), structured.result());
        }
    }

    @Test
    void rejectedCredentialsRequireAuthorization() {
        try (var session = clients.open(url, Map.of("Authorization", "Bearer revoked"), DEADLINE)) {
            assertCode("MCP_AUTHORIZATION_REQUIRED", session::listTools);
        }
        try (var session = clients.open(url, Map.of("Authorization", "Bearer forbidden"), DEADLINE)) {
            assertCode("MCP_AUTHORIZATION_REQUIRED", session::listTools);
        }
    }

    @Test
    void textThatLooksLikeAnOmissionMarkerIsStillText() {
        var result = McpSchema.CallToolResult.builder()
                .addTextContent("[{\"id\":1}]").structuredContent(Map.of("id", 1)).build();

        assertEquals("[{\"id\":1}]", McpSession.text(result));
    }

    @Test
    void callsStopAtTheTurnDeadline() {
        try (var session = clients.open(url, GOOD, Duration.ofMillis(700))) {
            session.listTools();
            assertCode("MCP_TIMEOUT", () -> session.call("slow", Map.of("text", "x")));
        }
    }

    @Test
    void unreachableServersAreUnavailableWithoutUpstreamDetail() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        try (var session = clients.open("http://127.0.0.1:" + closedPort + "/mcp", GOOD, DEADLINE)) {
            var failure = assertThrows(McpException.class, session::listTools);
            assertTrue(Map.of("MCP_UNAVAILABLE", 1, "MCP_TIMEOUT", 1).containsKey(failure.code()), failure.code());
            assertFalse(failure.safeMessage().contains("127.0.0.1"));
        }
    }

    @Test
    void endpointAndHeaderPolicyRejectsUnsafeValuesButAllowsInternalHosts() {
        McpClients.endpoint("http://10.0.0.5:8080/mcp");
        McpClients.endpoint("https://drivemcp.googleapis.com/mcp/v1");
        for (String invalid : List.of("ftp://host/mcp", "https://user:pass@host/mcp", "https://host/mcp?token=1",
                "https://host/mcp#x", "not a url", "/relative")) {
            assertThrows(IllegalArgumentException.class, () -> McpClients.endpoint(invalid), invalid);
        }
        McpClients.requireHeaders(Map.of("Authorization", "Bearer x", "X-Api-Key", "k"));
        assertThrows(IllegalArgumentException.class, () -> McpClients.requireHeaders(Map.of("Mcp-Session-Id", "s")));
        assertThrows(IllegalArgumentException.class, () -> McpClients.requireHeaders(Map.of("Host", "evil")));
        assertThrows(IllegalArgumentException.class, () -> McpClients.requireHeaders(Map.of("X-Key", "a\r\nInjected: b")));
        assertThrows(IllegalArgumentException.class, () -> McpClients.requireHeaders(Map.of("Bad Name", "v")));
    }

    private static McpServerFeatures.SyncToolSpecification tool(
            String name, boolean readOnly, Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
        return tool(name, readOnly, null, handler);
    }

    private static McpServerFeatures.SyncToolSpecification tool(String name, boolean readOnly,
            @Nullable Map<String, Object> meta, Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
        var schema = Map.<String, Object>of("type", "object",
                "properties", Map.of("text", Map.of("type", "string")));
        var tool = McpSchema.Tool.builder(name, schema).description(name + " tool").meta(meta)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly).build()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> handler.apply(request.arguments())).build();
    }

    private static McpServerFeatures.SyncResourceSpecification resource(String uri, @Nullable Map<String, Object> listedMeta,
            String mimeType, String text, @Nullable Map<String, Object> readMeta) {
        var resource = McpSchema.Resource.builder(uri, uri).mimeType(mimeType).meta(listedMeta).build();
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) ->
                new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(uri, mimeType, text, readMeta)), null));
    }

    private static void assertCode(String code, Executable action) {
        assertEquals(code, assertThrows(McpException.class, action).code());
    }

    /** The resource server's bearer check in front of the SDK servlet. */
    private static final class BearerServlet extends HttpServlet {
        private final HttpServlet delegate;

        private BearerServlet(HttpServlet delegate) {
            this.delegate = delegate;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            if ("Bearer forbidden".equals(request.getHeader("Authorization"))) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\"");
                return;
            }
            if (!"Bearer good".equals(request.getHeader("Authorization"))) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setHeader("WWW-Authenticate", "Bearer");
                return;
            }
            delegate.service(request, response);
        }
    }
}
