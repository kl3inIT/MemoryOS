package io.memoryos.api.mcp;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.apache.catalina.startup.Tomcat;
import org.jspecify.annotations.Nullable;

/**
 * In-process Streamable HTTP MCP server that requires the configured request headers. With an authorization
 * server it also publishes RFC 9728 protected-resource metadata and names it in the 401 challenge.
 */
public final class McpFixtureServer implements AutoCloseable {
    public static final String LONG_TOOL_NAME = "tool_name_that_cannot_fit_inside_the_model_tool_name_limit";
    private static final String METADATA_PATH = "/.well-known/oauth-protected-resource/mcp";

    private final Tomcat tomcat;
    private final McpSyncServer server;
    private final String url;

    private McpFixtureServer(Tomcat tomcat, McpSyncServer server, String url) {
        this.tomcat = tomcat; this.server = server; this.url = url;
    }

    public static McpFixtureServer start(Map<String, String> requiredHeaders) throws Exception {
        return start(requiredHeaders, null);
    }

    public static McpFixtureServer startOAuth(String requiredAuthorization, String authorizationServer) throws Exception {
        return start(Map.of("Authorization", requiredAuthorization), authorizationServer);
    }

    private static McpFixtureServer start(Map<String, String> requiredHeaders, @Nullable String authorizationServer) throws Exception {
        var transport = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
        var schema = Map.<String, Object>of("type", "object", "properties", Map.of("query", Map.of("type", "string")));
        var server = McpServer.sync(transport)
                .serverInfo("fixture", "1")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tool("search_files", "Search files", schema, true), tool(LONG_TOOL_NAME, null, schema, false))
                .build();
        var tomcat = new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("mcp-fixture").toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        tomcat.start();
        String origin = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort();
        var context = tomcat.addContext("", null);
        String challenge = authorizationServer == null ? "Bearer" : "Bearer resource_metadata=\"" + origin + METADATA_PATH + "\"";
        var servlet = Tomcat.addServlet(context, "mcp", new RequiredHeadersServlet(transport, requiredHeaders, challenge));
        servlet.setAsyncSupported(true);
        context.addServletMappingDecoded("/mcp", "mcp");
        if (authorizationServer != null) {
            Tomcat.addServlet(context, "metadata", new MetadataServlet("{\"resource\":\"" + origin + "/mcp\",\"authorization_servers\":[\""
                    + authorizationServer + "\"]}"));
            context.addServletMappingDecoded(METADATA_PATH, "metadata");
        }
        return new McpFixtureServer(tomcat, server, origin + "/mcp");
    }

    public String url() { return url; }

    @Override
    public void close() throws Exception {
        server.closeGracefully();
        tomcat.stop();
        tomcat.destroy();
    }

    private static McpServerFeatures.SyncToolSpecification tool(String name, String title, Map<String, Object> schema, boolean readOnly) {
        var tool = McpSchema.Tool.builder().name(name).title(title).description(name + " fixture").inputSchema(schema)
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly).destructiveHint(!readOnly).build()).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> McpSchema.CallToolResult.builder().addTextContent("ok").build()).build();
    }

    private static final class RequiredHeadersServlet extends HttpServlet {
        private final HttpServlet delegate;
        private final Map<String, String> requiredHeaders;
        private final String challenge;

        private RequiredHeadersServlet(HttpServlet delegate, Map<String, String> requiredHeaders, String challenge) {
            this.delegate = delegate; this.requiredHeaders = Map.copyOf(requiredHeaders); this.challenge = challenge;
        }

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            for (var header : requiredHeaders.entrySet()) {
                if (!header.getValue().equals(request.getHeader(header.getKey()))) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setHeader("WWW-Authenticate", challenge);
                    return;
                }
            }
            delegate.service(request, response);
        }
    }

    private static final class MetadataServlet extends HttpServlet {
        private final byte[] body;

        private MetadataServlet(String body) {
            this.body = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            response.setContentType("application/json");
            response.getOutputStream().write(body);
        }
    }
}
