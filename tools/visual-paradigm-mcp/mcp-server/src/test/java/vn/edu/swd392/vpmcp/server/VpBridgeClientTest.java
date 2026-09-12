package vn.edu.swd392.vpmcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VpBridgeClientTest {
  private HttpServer server;
  private VpBridgeClient client;

  @BeforeEach
  void startBridgeStub() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/tools",
        exchange ->
            respond(
                exchange,
                200,
                """
                {
                  "contractVersion": "1.0",
                  "catalogVersion": "test-catalog",
                  "tools": [{
                    "name": "createStateDiagram",
                    "description": "Create a state diagram",
                    "inputSchema": {
                      "type": "object",
                      "properties": {"diagramName": {"type": "string"}},
                      "required": ["diagramName"]
                    },
                    "annotations": {
                      "readOnlyHint": false,
                      "destructiveHint": false,
                      "idempotentHint": false
                    }
                  }]
                }
                """));
    server.createContext(
        "/execute",
        exchange ->
            respond(
                exchange,
                200,
                """
                {
                  "contractVersion": "1.0",
                  "requestId": "request-ok",
                  "success": true,
                  "result": "Created state diagram: EMS"
                }
                """));
    server.start();
    client =
        new VpBridgeClient(
            "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper());
  }

  @AfterEach
  void stopBridgeStub() {
    server.stop(0);
  }

  @Test
  void discoversAndExecutesBridgeTool() {
    BridgeTool tool = client.listTools().get(0);
    VpBridgeToolCallback callback = new VpBridgeToolCallback(tool, client);

    assertEquals("createStateDiagram", callback.getToolDefinition().name());
    assertEquals(
        "Created state diagram: EMS", callback.call("{\"diagramName\":\"State - EMS\"}"));
  }

  @Test
  void preservesStructuredBridgeFailureForTheMcpClient() {
    server.removeContext("/execute");
    server.createContext(
        "/execute",
        exchange ->
            respond(
                exchange,
                409,
                """
                {
                  "contractVersion": "1.0",
                  "requestId": "request-conflict",
                  "success": false,
                  "code": "REVISION_CONFLICT",
                  "category": "CONFLICT",
                  "retryable": false,
                  "applied": "NONE",
                  "field": "$.expectedRevision",
                  "message": "Diagram changed since inspection"
                }
                """));

    BridgeCallException exception =
        assertThrows(
            BridgeCallException.class,
            () -> client.execute("layoutContextElement", "{}"));

    assertTrue(exception.getMessage().contains("\"code\":\"REVISION_CONFLICT\""));
    assertTrue(exception.getMessage().contains("\"field\":\"$.expectedRevision\""));
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
