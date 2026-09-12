package vn.edu.swd392.vpmcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class McpToolConfiguration {
  private static final Logger LOGGER = LoggerFactory.getLogger(McpToolConfiguration.class);

  @Bean
  VpBridgeClient vpBridgeClient(@Value("${vp.bridge.base-url}") String baseUrl) {
    return new VpBridgeClient(baseUrl, new ObjectMapper());
  }

  @Bean
  ToolCallbackProvider visualParadigmTools(
      VpBridgeClient client, @Value("${vp.bridge.required-on-startup:true}") boolean required) {
    try {
      List<ToolCallback> callbacks =
          client.listTools().stream()
              .map(tool -> (ToolCallback) new VpBridgeToolCallback(tool, client))
              .toList();
      LOGGER.info("Loaded {} Visual Paradigm MCP tools from the Java 11 bridge", callbacks.size());
      return ToolCallbackProvider.from(callbacks);
    } catch (IllegalStateException exception) {
      if (required) {
        throw exception;
      }
      LOGGER.warn(
          "Visual Paradigm bridge is unavailable, so this MCP process starts without VP tools: {}",
          exception.getMessage());
      return ToolCallbackProvider.from(List.of());
    }
  }
}
