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
  private static final int EXECUTION_CACHE_SIZE = 256;

  private final ObjectMapper mapper = new ObjectMapper();
  private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();
  private final Map<String, CachedExecution> completedExecutions =
      new LinkedHashMap<String, CachedExecution>(EXECUTION_CACHE_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedExecution> eldest) {
          return size() > EXECUTION_CACHE_SIZE;
        }
      };
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
  private final ActivityLog activity = new ActivityLog();
  private final int port;
  private Undertow server;

  public VpBridgeServer(int port) {
    this.port = port;
  }

  int port() {
    return port;
  }

  ActivityLog activity() {
    return activity;
  }


  int toolCount() {
    return tools.size();
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
    if ("/".equals(path) || "/index.html".equals(path)) {
      sendUi(exchange);
      return;
    }
    if ("/activity".equals(path) && exchange.getRequestMethod().equals(Methods.GET)) {
      sendActivity(exchange);
      return;
    }
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

  private void sendUi(HttpServerExchange exchange) {
    exchange.setStatusCode(200);
    exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8");
    exchange.getResponseSender().send(UiPage.HTML);
  }

  private void sendActivity(HttpServerExchange exchange) {
    long after = 0;
    var params = exchange.getQueryParameters().get("after");
    if (params != null && !params.isEmpty()) {
      try {
        after = Long.parseLong(params.getFirst());
      } catch (NumberFormatException ignored) {
        after = 0;
      }
    }
    ObjectNode response = mapper.createObjectNode();
    response.put("latestSeq", activity.latest());
    response.set("entries", activity.snapshot(after, mapper));
    sendJson(exchange, 200, response);
  }

  private void recordActivity(
      long startNanos, String toolName, JsonNode arguments, JsonNode response, boolean cached) {
    ObjectNode entry = mapper.createObjectNode();
    entry.put("ts", System.currentTimeMillis());
    entry.put("durationMs", (System.nanoTime() - startNanos) / 1_000_000L);
    entry.put("toolName", toolName);
    entry.put("success", response.path("success").asBoolean());
    entry.put("code", response.path("code").asText(""));
    entry.put("cached", cached);
    ToolDefinition def = tools.get(toolName);
    entry.put("readOnly", def != null && def.readOnly());
    JsonNode args = arguments == null ? mapper.nullNode() : arguments;
    String argsText = args.toString();
    entry.put("arguments", argsText.length() > 2000 ? argsText.substring(0, 2000) + "…" : argsText);
    String resultText = response.toString();
    entry.put("resultPreview", resultText.length() > 4000 ? resultText.substring(0, 4000) + "…" : resultText);
    activity.add(entry);
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
    long startNanos = System.nanoTime();
    String requestId = UUID.randomUUID().toString();
    String toolName = "";
    JsonNode arguments = null;
    try {
      JsonNode request = mapper.readTree(exchange.getInputStream());
      if (request == null || !request.isObject()) {
        throw BridgeException.invalidArgument("$", "Bridge request must be a JSON object");
      }
      if (request.hasNonNull("requestId") && request.path("requestId").isTextual()) {
        requestId = request.path("requestId").asText();
      }
      toolName = request.path("toolName").asText("");
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
      arguments = request.get("arguments");
      String fingerprint = sha256(toolName + "\n" + mapper.writeValueAsString(request.path("arguments")));
      CachedExecution cached = tool.idempotent() ? completedExecutions.get(requestId) : null;
      if (cached != null) {
        if (!cached.fingerprint.equals(fingerprint)) {
          throw BridgeException.conflict(
              "IDEMPOTENCY_CONFLICT",
              "$.requestId",
              "requestId was already used for a different tool call");
        }
        recordActivity(startNanos, toolName, arguments, cached.response, true);
        sendJson(exchange, 200, cached.response.deepCopy());
        return;
      }
      Object result = tool.execute(request.get("arguments"));
      if (!tool.readOnly()) {
        DesignQaRegistry.get().noteMutation(toolName, arguments);
      }
      ObjectNode response = mapper.createObjectNode();
      response.put("contractVersion", CONTRACT_VERSION);
      response.put("requestId", requestId);
      response.put("success", true);
      response.put("code", "OK");
      response.put("applied", tool.readOnly() ? "NONE" : "APPLIED");
      response.set("result", mapper.valueToTree(result));
      if (tool.idempotent()) {
        completedExecutions.put(requestId, new CachedExecution(fingerprint, response.deepCopy()));
      }
      sendJson(exchange, 200, response);
      recordActivity(startNanos, toolName, arguments, response, false);
    } catch (BridgeException exception) {
      recordActivity(startNanos, toolName, arguments, sendError(exchange, requestId, exception), false);
    } catch (IllegalArgumentException exception) {
      recordActivity(
          startNanos,
          toolName,
          arguments,
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
                  safeMessage(exception))),
          false);
    } catch (IllegalStateException exception) {
      recordActivity(
          startNanos,
          toolName,
          arguments,
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
                  safeMessage(exception))),
          false);
    } catch (Exception exception) {
      recordActivity(
          startNanos,
          toolName,
          arguments,
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
                      safeMessage(exception) + " @" + topFrame(exception))),
          false);
    }
  }

  private ObjectNode sendError(
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
    return response;
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

  private static String topFrame(Throwable throwable) {
    StackTraceElement[] frames = throwable.getStackTrace();
    if (frames == null || frames.length == 0) {
      return "?";
    }
    StringBuilder trace = new StringBuilder();
    int limit = Math.min(4, frames.length);
    for (int index = 0; index < limit; index++) {
      StackTraceElement frame = frames[index];
      trace.append(frame.getClassName()).append('.').append(frame.getMethodName())
          .append(':').append(frame.getLineNumber());
      if (index + 1 < limit) {
        trace.append(" <- ");
      }
    }
    return trace.toString();
  }

  private static String safeMessage(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
  }

  private static final class CachedExecution {
    private final String fingerprint;
    private final ObjectNode response;

    private CachedExecution(String fingerprint, ObjectNode response) {
      this.fingerprint = fingerprint;
      this.response = response;
    }
  }
}
