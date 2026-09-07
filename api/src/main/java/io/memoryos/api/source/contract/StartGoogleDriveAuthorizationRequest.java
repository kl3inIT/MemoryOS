package io.memoryos.api.source.contract;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Schema(name = "StartGoogleDriveAuthorizationRequest", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record StartGoogleDriveAuthorizationRequest(
        @NotBlank @Size(max = 120) @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Nullable UUID credentialId,
        @Positive @Nullable Long expectedCredentialRevision,
        @Size(max = 16384) @Nullable String oauthClientJson) {
    @Override public String toString() { return "StartGoogleDriveAuthorizationRequest[redacted]"; }
}
