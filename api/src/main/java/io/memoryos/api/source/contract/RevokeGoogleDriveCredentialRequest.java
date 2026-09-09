package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;

@Schema(name = "RevokeGoogleDriveCredentialRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record RevokeGoogleDriveCredentialRequest(
        @Positive @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long expectedCredentialRevision) {}
