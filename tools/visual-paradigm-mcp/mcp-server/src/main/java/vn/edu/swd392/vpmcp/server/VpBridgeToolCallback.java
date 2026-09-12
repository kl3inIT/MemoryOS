package vn.edu.swd392.vpmcp.server;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

final class VpBridgeToolCallback implements ToolCallback {
  private final ToolDefinition definition;
  private final VpBridgeClient client;

  VpBridgeToolCallback(BridgeTool tool, VpBridgeClient client) {
    this.definition =
        new DefaultToolDefinition(
            tool.name(), tool.description(), tool.inputSchema().toString());
    this.client = client;
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return definition;
  }

  @Override
  public String call(String toolInput) {
    return client.execute(definition.name(), toolInput);
  }
}
