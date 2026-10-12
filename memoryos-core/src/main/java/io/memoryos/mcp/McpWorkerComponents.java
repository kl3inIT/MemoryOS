package io.memoryos.mcp;

import io.memoryos.mcp.persistence.JdbcMcpEndpointCallRepository;
import org.springframework.context.annotation.Import;

/**
 * The MCP beans the Worker runs, for a Worker that does not scan the module: the activity log's retention (MEM-209).
 * The Worker imports this class; it is deliberately not a component, so the API registers these beans only once.
 */
@Import({JdbcMcpEndpointCallRepository.class, McpEndpointCallRetention.class})
public class McpWorkerComponents {
}
