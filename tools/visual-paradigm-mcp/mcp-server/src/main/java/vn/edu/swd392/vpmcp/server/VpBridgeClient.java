package vn.edu.swd392.vpmcp.server;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

final class VpBridgeClient {
  private final URI baseUrl;
  private final HttpClient httpClient;
  private final ObjectMapper mapper;

  VpBridgeClient(String baseUrl, ObjectMapper mapper) {
    this.baseUrl = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
    this.mapper = mapper;
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  }

  List<BridgeTool> listTools() {
    HttpRequest request =
        HttpRequest.newBuilder(baseUrl.resolve("tools"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    HttpResponse<String> response = send(request);
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Visual Paradigm bridge /tools returned HTTP " + response.statusCode());
    }
    try {
      JsonNode body = mapper.readTree(response.body());
      JsonNode tools = body.isArray() ? body : body.path("tools");
      if (!tools.isArray()) {
        throw new IllegalStateException("Visual Paradigm tool catalog does not contain tools[]");
      }
      return mapper.convertValue(tools, new TypeReference<List<BridgeTool>>() {});
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot parse Visual Paradigm tool catalog", exception);
    }
  }

  String execute(String toolName, String argumentsJson) {
    try {
      ObjectNode payload = mapper.createObjectNode();
      payload.put("requestId", UUID.randomUUID().toString());
      payload.put("toolName", toolName);
      JsonNode arguments =
          argumentsJson == null || argumentsJson.isBlank()
              ? mapper.createObjectNode()
              : mapper.readTree(argumentsJson);
      payload.set("arguments", arguments);
      HttpRequest request =
          HttpRequest.newBuilder(baseUrl.resolve("execute"))
              .timeout(Duration.ofSeconds(30))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
              .build();
      HttpResponse<String> response = send(request);
      JsonNode body = mapper.readTree(response.body());
      boolean successful =
          response.statusCode() == 200
              && (!body.has("success") || body.path("success").asBoolean());
      if (!successful || body.has("error")) {
        String legacyMessage = body.path("error").asText("");
        throw new BridgeCallException(
            body.path("requestId").asText(""),
            body.path("code").asText("BRIDGE_ERROR"),
            body.path("category").asText("INTERNAL"),
            body.path("retryable").asBoolean(false),
            body.path("applied").asText("UNKNOWN"),
            body.path("field").asText(""),
            body.path("message")
                .asText(
                    legacyMessage.isBlank()
                        ? "Bridge returned HTTP " + response.statusCode()
                        : legacyMessage));
      }
      JsonNode result = body.path("result");
      return result.isTextual() ? result.textValue() : mapper.writeValueAsString(result);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot encode Visual Paradigm tool call", exception);
    }
  }

  private HttpResponse<String> send(HttpRequest request) {
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Cannot reach Visual Paradigm bridge at " + baseUrl + ". Start Visual Paradigm first.",
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Visual Paradigm bridge request was interrupted", exception);
    }
  }
}
