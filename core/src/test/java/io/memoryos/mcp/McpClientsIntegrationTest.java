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
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
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
                        }))
                .build();
        tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("mcp-tomcat").toString());
        tomcat.setPort(0);
        var context = tomcat.addContext("", null);
        var servlet = Tomcat.addServlet(context, "mcp", new BearerServlet(transport));
        servlet.setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
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

            assertEquals(List.of("echo", "fail", "structured", "slow"), tools.stream().map(McpToolDescriptor::name).toList());
            var echo = tools.getFirst();
            assertEquals(Boolean.TRUE, echo.readOnlyHint());
            assertEquals("object", echo.inputSchema().get("type"));
            assertEquals(Boolean.FALSE, tools.get(1).readOnlyHint());
        }
    }

    @Test
    void callsToolsAndConvertsResultsForTheModel() {
        try (var session = clients.open(url, GOOD, DEADLINE)) {
            assertEquals(new McpToolResult("echo: hi", false), session.call("echo", Map.of("text", "hi")));
            assertEquals(new McpToolResult("bad input", true), session.call("fail", Map.of("text", "x")));
            assertEquals(new McpToolResult("{\"count\":2}", false), session.call("structured", Map.of("text", "x")));
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
        var schema = Map.<String, Object>of("type", "object",
                "properties", Map.of("text", Map.of("type", "string")));
        var tool = McpSchema.Tool.builder().name(name).description(name + " tool").inputSchema(schema)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly).build()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> handler.apply(request.arguments())).build();
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
