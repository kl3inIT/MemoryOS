package io.memoryos.api.mcp.endpoint;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * MEM-114 bounds of the MCP endpoint. Calls are tools/call requests, counted per caller (a person through one client)
 * and for the whole endpoint; a search embeds the query, so these are also the endpoint's cost bound. The buckets live
 * in this process, like the single API node they protect.
 */
@Validated
@ConfigurationProperties("memoryos.mcp.endpoint")
public record McpEndpointLimits(
        @Min(1) @Max(10_000) int callerCallsPerMinute,
        @Min(1) @Max(100_000) int globalCallsPerMinute,
        @Min(1_024) @Max(4_194_304) int maxRequestBytes) {
}
