package io.memoryos.api.invitation.contract;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(name = "CurrentInvitation", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CurrentInvitationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        String tenantDisplayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant expiresAt,
        @Schema(pattern = "^/invite/continue", requiredMode = Schema.RequiredMode.REQUIRED)
        String continueUrl
) {
}
