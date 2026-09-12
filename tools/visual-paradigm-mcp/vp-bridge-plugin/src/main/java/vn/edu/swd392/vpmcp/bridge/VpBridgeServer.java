package vn.edu.swd392.vpmcp.bridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class VpBridgeServer implements AutoCloseable {
  private static final String CONTRACT_VERSION = "1.0";
  private static final long MAX_REQUEST_BYTES = 1024L * 1024L;

  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
  private final ThreadPoolExecutor commands =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(64),
          runnable -> {
            Thread thread = new Thread(runnable, "vp-mcp-command");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private final int port;
  private Undertow server;

  public VpBridgeServer(int port) {
    this.port = port;
  }

  public void register(Object... services) {
    for (Object service : services) {
      for (ToolDefinition definition : ToolDefinition.scan(service, mapper)) {
        ToolDefinition previous = tools.putIfAbsent(definition.name(), definition);
        if (previous != null) {
          throw new IllegalArgumentException("Duplicate tool name: " + definition.name());
        }
      }
    }
  }

  public void start() {
    if (server != null) {
      return;
    }
    server =
        Undertow.builder()
            .addHttpListener(port, "127.0.0.1")
            .setHandler(this::route)
            .setIoThreads(2)
            .setWorkerThreads(4)
            .build();
    server.start();
    System.out.println(
        "SWD392 VP bridge listening on http://127.0.0.1:"
            + port
            + " with "
            + tools.size()
            + " tools");
  }

  private void route(HttpServerExchange exchange) {
    String path = exchange.getRequestPath();
    if ("/health".equals(path) && exchange.getRequestMethod().equals(Methods.GET)) {
      sendHealth(exchange);
    } else if ("/tools".equals(path) && exchange.getRequestMethod().equals(Methods.GET)) {
      sendTools(exchange);
    } else if ("/execute".equals(path) && exchange.getRequestMethod().equals(Methods.POST)) {
      exchange.setMaxEntitySize(MAX_REQUEST_BYTES);
      if (exchange.getRequestContentLength() > MAX_REQUEST_BYTES) {
        sendError(
            exchange,
            UUID.randomUUID().toString(),
            new BridgeException(
                413,
                "REQUEST_TOO_LARGE",
                "VALIDATION",
                false,
                "NONE",
                "$",
                "Bridge request exceeds 1 MiB"));
        return;
      }
      try {
        exchange.dispatch(commands, () -> execute(exchange));
      } catch (RejectedExecutionException exception) {
        sendError(
            exchange,
            UUID.randomUUID().toString(),
            new BridgeException(
                503,
                "BRIDGE_BUSY",
                "TRANSIENT",
                true,
                "NONE",
                "",
                "Visual Paradigm mutation queue is full"));
      }
    } else {
      exchange.setStatusCode(404);
      exchange.endExchange();
    }
  }

  private void sendHealth(HttpServerExchange exchange) {
    ObjectNode response = mapper.createObjectNode();
    response.put("status", "UP");
    response.put("contractVersion", CONTRACT_VERSION);
    response.put("catalogVersion", catalogVersion());
    response.put("toolCount", tools.size());
    response.put("queuedCommands", commands.getQueue().size());
    sendJson(exchange, 200, response);
  }

  private void sendTools(HttpServerExchange exchange) {
    ArrayNode catalog = toolCatalog();
    ObjectNode response = mapper.createObjectNode();
    response.put("contractVersion", CONTRACT_VERSION);
    response.put("catalogVersion", sha256(catalog.toString()));
    response.set("tools", catalog);
    sendJson(exchange, 200, response);
  }

  private void execute(HttpServerExchange exchange) {
    exchange.startBlocking();
    String requestId = UUID.randomUUID().toString();
    try {
      JsonNode request = mapper.readTree(exchange.getInputStream());
      if (request == null || !request.isObject()) {
        throw BridgeException.invalidArgument("$", "Bridge request must be a JSON object");
      }
      if (request.hasNonNull("requestId") && request.path("requestId").isTextual()) {
        requestId = request.path("requestId").asText();
      }
      String toolName = request.path("toolName").asText("");
      if (toolName.isBlank()) {
        throw BridgeException.invalidArgument("$.toolName", "toolName is required");
      }
      ToolDefinition tool = tools.get(toolName);
      if (tool == null) {
        throw new BridgeException(
            404,
            "UNKNOWN_TOOL",
            "VALIDATION",
            false,
            "NONE",
            "$.toolName",
            "Unknown tool: " + toolName);
      }
      Object result = tool.execute(request.get("arguments"));
      ObjectNode response = mapper.createObjectNode();
      response.put("contractVersion", CONTRACT_VERSION);
      response.put("requestId", requestId);
      response.put("success", true);
      response.put("code", "OK");
      response.put("applied", tool.readOnly() ? "NONE" : "APPLIED");
      response.set("result", mapper.valueToTree(result));
      sendJson(exchange, 200, response);
    } catch (BridgeException exception) {
      sendError(exchange, requestId, exception);
    } catch (IllegalArgumentException exception) {
      sendError(
          exchange,
          requestId,
          new BridgeException(
              400,
              "INVALID_ARGUMENT",
              "VALIDATION",
              false,
              "NONE",
              "",
              safeMessage(exception)));
    } catch (IllegalStateException exception) {
      sendError(
          exchange,
          requestId,
          new BridgeException(
              409,
              "INVALID_STATE",
              "STATE",
              false,
              "UNKNOWN",
              "",
              safeMessage(exception)));
    } catch (Exception exception) {
      sendError(
          exchange,
          requestId,
          new BridgeException(
              500,
              "INTERNAL_ERROR",
              "INTERNAL",
              false,
              "UNKNOWN",
              "",
              safeMessage(exception)));
    }
  }

  private void sendError(
      HttpServerExchange exchange, String requestId, BridgeException exception) {
    ObjectNode response = mapper.createObjectNode();
    response.put("contractVersion", CONTRACT_VERSION);
    response.put("requestId", requestId);
    response.put("success", false);
    response.put("code", exception.code());
    response.put("category", exception.category());
    response.put("retryable", exception.retryable());
    response.put("applied", exception.applied());
    if (exception.field() != null && !exception.field().isBlank()) {
      response.put("field", exception.field());
    }
    response.put("message", safeMessage(exception));
    sendJson(exchange, exception.httpStatus(), response);
  }

  private void sendJson(HttpServerExchange exchange, int status, JsonNode response) {
    try {
      byte[] bytes = mapper.writeValueAsBytes(response);
      exchange.setStatusCode(status);
      exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=UTF-8");
      exchange.getResponseSender().send(new String(bytes, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      exchange.setStatusCode(500);
      exchange.endExchange();
    }
  }

  @Override
  public void close() {
    if (server != null) {
      server.stop();
      server = null;
    }
    commands.shutdownNow();
  }

  private ArrayNode toolCatalog() {
    ArrayNode result = mapper.createArrayNode();
    tools.values().stream()
        .sorted((left, right) -> left.name().compareTo(right.name()))
        .forEach(tool -> result.add(tool.toJson(mapper)));
    return result;
  }

  private String catalogVersion() {
    return sha256(toolCatalog().toString());
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(hash.length * 2);
      for (byte item : hash) {
        result.append(String.format("%02x", item & 0xff));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String safeMessage(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
  }
}
