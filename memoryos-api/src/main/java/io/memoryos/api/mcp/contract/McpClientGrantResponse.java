package io.memoryos.api.mcp.contract;

import io.memoryos.iam.McpClientGrant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record McpClientGrantResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Keycloak's client ID; Claude's is the URL of its client metadata document")
        String clientId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) McpClientGrant.Client client,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant grantedAt) {
    public static McpClientGrantResponse from(McpClientGrant grant) {
        return new McpClientGrantResponse(grant.clientId(), grant.client(), grant.name(), grant.grantedAt());
    }
}
