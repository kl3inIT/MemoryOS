package io.memoryos.api.mcp.contract;

import io.memoryos.mcp.McpOAuthClientSource;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "McpOAuthRegistration", description = "Source REGISTERED uses DCR; METADATA_DOCUMENT uses the MemoryOS client metadata document")
public record McpOAuthRegistrationRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 100) String label,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "An issuer from the discovery review") String issuer,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpOAuthClientSource source
) {}
