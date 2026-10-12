package io.memoryos.api.invitation.contract;

import io.swagger.v3.oas.annotations.media.Schema;

import io.memoryos.iam.InvitationStatus;

import java.time.Instant;
import java.util.UUID;

@Schema(name = "Invitation", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record InvitationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(format = "email", requiredMode = Schema.RequiredMode.REQUIRED)
        String email,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        InvitationStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Instant expiresAt,
        @Schema(nullable = true)
        UUID acceptedActorId,
        @Schema(nullable = true)
        Instant acceptedAt,
        @Schema(nullable = true)
        Instant revokedAt
) {
}
