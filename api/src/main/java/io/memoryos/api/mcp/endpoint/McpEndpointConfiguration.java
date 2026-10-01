package io.memoryos.api.mcp.endpoint;

import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MEM-114: publishes exactly the tools of {@link McpEndpointTools}. Spring AI builds {@code @McpTool} specifications
 * only while its {@code ToolCallback} converter is on, and that converter would also publish every Chat tool, so the
 * converter and the annotation scanner stay off and the endpoint's tools are registered here.
 */
@Configuration(proxyBeanMethods = false)
class McpEndpointConfiguration {

    @Bean
    List<SyncToolSpecification> mcpEndpointToolSpecifications(McpEndpointTools tools) {
        return SyncMcpAnnotationProviders.statelessToolSpecifications(List.of(tools)).stream()
                .map(McpEndpointConfiguration::oneSentenceFailures)
                .toList();
    }

    /**
     * Spring AI writes a failed call as the exception's message followed by its root cause's. A tool failure has no
     * cause, so the client would read the same sentence twice; only the first line is kept.
     */
    private static SyncToolSpecification oneSentenceFailures(SyncToolSpecification specification) {
        return SyncToolSpecification.builder().tool(specification.tool()).callHandler((context, request) -> {
            CallToolResult result = specification.callHandler().apply(context, request);
            if (!Boolean.TRUE.equals(result.isError()) || result.content().isEmpty()
                    || !(result.content().getFirst() instanceof TextContent text)) {
                return result;
            }
            String sentence = text.text().lines().findFirst().orElse(McpEndpointTools.ToolFailure.FAILED);
            return CallToolResult.builder().isError(true).addTextContent(sentence).build();
        }).build();
    }
}
